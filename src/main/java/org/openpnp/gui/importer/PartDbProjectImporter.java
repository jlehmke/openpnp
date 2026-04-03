package org.openpnp.gui.importer;

import java.awt.Frame;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JOptionPane;

import org.openpnp.gui.ProjectBoardImportDialog;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.model.ProjectFile;
import org.openpnp.model.ProjectRecord;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PartDatabase;
import org.openpnp.spi.ProjectStorage;
import org.pmw.tinylog.Logger;

/**
 * BoardImporter that creates an OpenPnP board from a PartDB project.
 *
 * <p>Workflow:
 * <ol>
 *   <li>User selects a PartDB project and a placement file attachment (.pos).</li>
 *   <li>Coordinates are parsed from the placement file.</li>
 *   <li>Part assignments come from the project BOM: each BOM entry maps a set of
 *       reference designators (mountnames) to a real PartDB part name.</li>
 *   <li>New parts are fully imported from PartDB (footprint, description, …).</li>
 *   <li>Placements whose designator is not in the BOM are disabled.</li>
 * </ol>
 *
 * <p>This class is discovered automatically by ClassGraph — a no-arg constructor
 * and placement in this package are sufficient.
 */
@SuppressWarnings("serial")
public class PartDbProjectImporter implements BoardImporter {

    private static final String NAME = "PartDB Project";
    private static final String DESCRIPTION =
            "Import placements from a PartDB project using the project BOM for part assignment.";

    @Override
    public String getImporterName() {
        return NAME;
    }

    @Override
    public String getImporterDescription() {
        return DESCRIPTION;
    }

    @Override
    public Board importBoard(Frame parent) throws Exception {
        ProjectStorage db = getProjectStorage();
        if (db == null || !db.isConnected()) {
            JOptionPane.showMessageDialog(parent,
                    "No project storage backend is connected.\n"
                            + "Please configure PartDB in Machine Settings \u2192 Integrations.",
                    "No Project Storage", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        // Let the user pick project + placement file
        ProjectBoardImportDialog dlg = new ProjectBoardImportDialog(parent, db, false);
        dlg.setVisible(true);

        ProjectRecord project = dlg.getSelectedProject();
        ProjectFile posFile = dlg.getSelectedFile();
        if (project == null || posFile == null) {
            return null;
        }

        return importBoardFromProjectStorage(parent, project, posFile, db,
                dlg.isImportMissingParts());
    }

    /**
     * Import a board given a pre-selected project, placement file, and storage backend.
     * Called directly from BoardsPanel to avoid showing the project-picker dialog twice.
     */
    public Board importBoardFromProjectStorage(Frame parent, ProjectRecord project,
            ProjectFile posFile, ProjectStorage db) throws Exception {
        return importBoardFromProjectStorage(parent, project, posFile, db, true, null);
    }

    public Board importBoardFromProjectStorage(Frame parent, ProjectRecord project,
            ProjectFile posFile, ProjectStorage db, boolean createMissingParts) throws Exception {
        return importBoardFromProjectStorage(parent, project, posFile, db, createMissingParts, null);
    }

    public Board importBoardFromProjectStorage(Frame parent, ProjectRecord project,
            ProjectFile posFile, ProjectStorage db, boolean createMissingParts,
            BiConsumer<Integer, Integer> progressCallback) throws Exception {
        // Download placement file to a temp file
        byte[] bytes = db.downloadFile(project.id, posFile.id);
        File tmp = File.createTempFile("openpnp-partdb-", ".pos");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), bytes);

        // Parse placements with correct side detection
        List<Placement> placements = parseFileWithSides(tmp);

        // Fetch BOM: designator → PartDB part name
        Map<String, String> bom = db.getDesignatorToPartName(project.id);
        if (bom.isEmpty()) {
            Logger.warn("PartDB project '{}' has no BOM entries. "
                    + "Parts will not be assigned.", project.name);
        }

        Configuration cfg = Configuration.get();

        // Collect unique part names that need importing for accurate progress reporting
        Set<String> missingPartNames = new LinkedHashSet<>();
        if (createMissingParts) {
            for (Placement p : placements) {
                String partName = bom.get(p.getId());
                if (partName != null && cfg.getPart(partName) == null) {
                    missingPartNames.add(partName);
                }
            }
        }
        int total = missingPartNames.size();
        if (progressCallback != null && total > 0) {
            progressCallback.accept(0, total);
        }

        // Import missing parts with progress
        int current = 0;
        for (String partName : missingPartNames) {
            importOrCreatePart(partName, db);
            current++;
            if (progressCallback != null) {
                progressCallback.accept(current, total);
            }
        }

        // Assign parts to placements; disable those not in BOM
        List<String> notInBom = new ArrayList<>();
        for (Placement p : placements) {
            String partName = bom.get(p.getId());
            if (partName != null) {
                Part part = cfg.getPart(partName);
                if (part != null) {
                    p.setPart(part);
                }
            } else {
                p.setEnabled(false);
                notInBom.add(p.getId());
            }
        }

        if (!notInBom.isEmpty()) {
            Logger.info("Disabled {} placement(s) not in PartDB BOM: {}",
                    notInBom.size(), String.join(", ", notInBom));
        }

        // Build and return the Board
        Board board = new Board();
        board.setName(project.name);
        for (Placement p : placements) {
            board.addPlacement(p);
        }
        return board;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ProjectStorage getProjectStorage() {
        Machine machine = Configuration.get().getMachine();
        if (machine instanceof ReferenceMachine) {
            PartDatabase db = ((ReferenceMachine) machine).getPartDatabase();
            if (db instanceof ProjectStorage) {
                return (ProjectStorage) db;
            }
        }
        return null;
    }

    public static Part importOrCreatePart(String partName, ProjectStorage db) {
        Configuration cfg = Configuration.get();
        if (db instanceof PartDatabase) {
            try {
                Part imported = ((PartDatabase) db).importPart(partName);
                if (imported != null) {
                    return imported;
                }
            } catch (Exception e) {
                Logger.warn("Could not import part '{}' from PartDB: {}", partName, e.getMessage());
            }
        }
        // Fallback: create a bare Part
        Part part = new Part(partName);
        cfg.addPart(part);
        return part;
    }

    /**
     * Parses a KiCad .pos placement file with correct side detection.
     *
     * <p>Handles both the "top/bottom" and "F.Cu/B.Cu" layer column notations.
     * Bottom-side coordinates are adjusted for OpenPnP's coordinate system
     * (X is negated and rotation is adjusted to 180-rot).
     */
    static List<Placement> parseFileWithSides(File file) throws Exception {
        List<Placement> placements = new ArrayList<>();

        // Same pattern as KicadPosImporter
        Pattern pattern = Pattern.compile(
                "(\\S+)\\s+(.*?)\\s+(.*?)\\s+(-?\\d+\\.\\d+)\\s+(-?\\d+\\.\\d+)\\s+(-?\\d+\\.\\d+)\\s(.*)");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                Matcher m = pattern.matcher(line);
                if (!m.matches()) {
                    continue;
                }

                String ref = m.group(1);
                double x = Double.parseDouble(m.group(4));
                double y = Double.parseDouble(m.group(5));
                double rot = Double.parseDouble(m.group(6));
                String layer = m.group(7).trim();

                boolean isBottom = isBottomLayer(layer);
                if (isBottom) {
                    // OpenPnP bottom-side convention: mirror X, adjust rotation
                    x = -x;
                    rot = 180.0 - rot;
                }
                if (rot == -0.0) {
                    rot = 0.0;
                }

                Placement p = new Placement(ref);
                p.setLocation(new Location(LengthUnit.Millimeters, x, y, 0, rot));
                p.setSide(isBottom ? Side.Bottom : Side.Top);
                placements.add(p);
            }
        }
        return placements;
    }

    /**
     * Returns {@code true} if the given layer string represents the bottom/back side.
     * Handles KiCad "B.Cu", "Bottom", "bottom", and short form "B".
     */
    private static boolean isBottomLayer(String layer) {
        if (layer == null || layer.isEmpty()) {
            return false;
        }
        String lower = layer.toLowerCase();
        return lower.equals("b.cu")
                || lower.equals("b")
                || lower.equals("bottom")
                || lower.contains("bottom");
    }
}
