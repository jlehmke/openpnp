package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JOptionPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import org.openpnp.Translations;
import org.openpnp.gui.importer.KicadModImporter;
import org.openpnp.gui.support.Icons;
import org.openpnp.machine.reference.KicadLibrary;
import org.openpnp.machine.reference.PartDbDatabase;
import org.openpnp.machine.reference.PartDbDatabase.PartDbLot;
import org.openpnp.machine.reference.PartDbDatabase.PartDbRawData;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Part;
import org.pmw.tinylog.Logger;

@SuppressWarnings("serial")
public class PartDbDetailsPanel extends JPanel {

    private static final int ROW_HEIGHT      = 0;
    private static final int ROW_BODY_WIDTH  = 1;
    private static final int ROW_BODY_LENGTH = 2;
    private static final int ROW_COUNT       = 3;

    private static final String[] ROW_NAMES = {
        "Height (mm)", "Body Width (mm)", "Body Length (mm)"
    };

    private static final int IMG_SIZE = 80;

    private final Part part;
    private final PartDbDatabase db;
    private final DetailsTableModel tableModel;
    private final LotsTableModel lotsTableModel;
    private final JTable lotsTable;
    private final JLabel statusLabel;
    private final JButton datasheetBtn;
    private final JButton viewInDbBtn;
    private final JSpinner qtySpinner;
    private final JButton addStockBtn;
    private final JButton removeStockBtn;
    private final JLabel imgLabel;
    private final JPanel imgWrapper;
    private final JTextField nameLabel;
    private final JLabel idLabel;
    private final JTextField mpnLabel;
    private final JTextField ipnLabel;
    private final JTextField pkgLabel;
    private final JTextField descLabel;
    private final JTextField kicadFootprintField;
    private final JButton applyPadsBtn;
    private final JButton selectKicadFpBtn;
    private PartDbRawData rawData;
    private String currentKicadRef;
    private BufferedImage imgOriginal;
    private boolean loaded;

    public PartDbDetailsPanel(Part part, PartDbDatabase db) {
        this.part = part;
        this.db = db;

        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // --- Header: image + info ---
        imgLabel = new JLabel();
        imgLabel.setHorizontalAlignment(JLabel.CENTER);
        imgLabel.setVerticalAlignment(JLabel.CENTER);
        // Square wrapper: preferred width tracks actual height so the box is always square.
        // Placed in BorderLayout.WEST so it fills the full header height automatically.
        imgWrapper = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                int h = getHeight() > 0 ? getHeight() : IMG_SIZE;
                return new Dimension(h, h);
            }
            @Override public Dimension getMinimumSize() { return new Dimension(IMG_SIZE, IMG_SIZE); }
        };
        imgWrapper.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        imgWrapper.add(imgLabel, BorderLayout.CENTER);
        imgWrapper.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                // Re-trigger layout once so width catches up to height
                Container parent = imgWrapper.getParent();
                if (parent != null && imgWrapper.getWidth() != imgWrapper.getHeight()) {
                    parent.revalidate();
                }
                rescaleImage();
            }
        });

        nameLabel = new JTextField(" ") {
            @Override public Dimension getMaximumSize() {
                return new Dimension(getPreferredSize().width, getPreferredSize().height);
            }
        };
        nameLabel.setEditable(false);
        nameLabel.setOpaque(false);
        nameLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        float baseFontSize = nameLabel.getFont().getSize2D();
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, baseFontSize * 1.33f));

        idLabel = new JLabel(" ");
        idLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));

        JPanel nameRow = new JPanel();
        nameRow.setLayout(new BoxLayout(nameRow, BoxLayout.X_AXIS));
        nameRow.setOpaque(false);
        nameRow.setAlignmentX(LEFT_ALIGNMENT);
        nameRow.add(nameLabel);
        nameRow.add(idLabel);
        nameRow.add(Box.createHorizontalGlue());
        nameRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameRow.getPreferredSize().height));

        mpnLabel = new JTextField(" ");
        mpnLabel.setEditable(false);
        mpnLabel.setOpaque(false);
        mpnLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        mpnLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, mpnLabel.getPreferredSize().height));

        ipnLabel = new JTextField(" ");
        ipnLabel.setEditable(false);
        ipnLabel.setOpaque(false);
        ipnLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        ipnLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, ipnLabel.getPreferredSize().height));

        pkgLabel = new JTextField(" ");
        pkgLabel.setEditable(false);
        pkgLabel.setOpaque(false);
        pkgLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        pkgLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pkgLabel.getPreferredSize().height));

        descLabel = new JTextField(" ");
        descLabel.setEditable(false);
        descLabel.setOpaque(false);
        descLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        descLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, descLabel.getPreferredSize().height));
        mpnLabel.setAlignmentX(LEFT_ALIGNMENT);
        ipnLabel.setAlignmentX(LEFT_ALIGNMENT);
        pkgLabel.setAlignmentX(LEFT_ALIGNMENT);
        descLabel.setAlignmentX(LEFT_ALIGNMENT);

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        infoPanel.add(nameRow);
        infoPanel.add(Box.createVerticalStrut(2));
        infoPanel.add(mpnLabel);
        infoPanel.add(ipnLabel);
        infoPanel.add(pkgLabel);
        infoPanel.add(Box.createVerticalStrut(2));
        infoPanel.add(descLabel);

        JPanel headerPanel = new JPanel(new BorderLayout(4, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        headerPanel.add(imgWrapper, BorderLayout.WEST);
        headerPanel.add(infoPanel, BorderLayout.CENTER);

        // --- Parameters table ---
        tableModel = new DetailsTableModel();
        JTable table = new JTable(tableModel) {
            @Override
            public void doLayout() {
                int total = getWidth();
                if (total > 0) {
                    getColumnModel().getColumn(0).setPreferredWidth((int) (total * 0.4));
                    getColumnModel().getColumn(1).setPreferredWidth((int) (total * 0.2));
                    getColumnModel().getColumn(2).setPreferredWidth((int) (total * 0.2));
                    getColumnModel().getColumn(3).setPreferredWidth((int) (total * 0.2));
                }
                super.doLayout();
            }
        };
        table.setDefaultRenderer(Object.class, new ConflictRenderer());
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        kicadFootprintField = new JTextField();
        kicadFootprintField.setEditable(false);
        applyPadsBtn = new JButton(Translations.getString("PartDbDetailsPanel.applyPadsButton.text"));
        applyPadsBtn.setEnabled(false);
        applyPadsBtn.setToolTipText("Import pad geometry from the KiCad library into the current package");
        applyPadsBtn.addActionListener(e -> applyKicadPads());

        selectKicadFpBtn = new JButton(Translations.getString("PartDbDetailsPanel.selectFootprintButton.text"));
        selectKicadFpBtn.setToolTipText("Browse the PartDB KiCad HTTP Library to select a footprint and apply its pads");
        selectKicadFpBtn.addActionListener(e -> selectKicadFootprint());

        JPanel kicadBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        kicadBtns.add(applyPadsBtn);
        kicadBtns.add(selectKicadFpBtn);

        JPanel kicadRow = new JPanel(new BorderLayout(4, 0));
        kicadRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        kicadRow.add(new JLabel(Translations.getString("PartDbDetailsPanel.kicadFootprintLabel.text")), BorderLayout.WEST);
        kicadRow.add(kicadFootprintField, BorderLayout.CENTER);
        kicadRow.add(kicadBtns, BorderLayout.EAST);

        JPanel paramsTable = new JPanel(new BorderLayout());
        paramsTable.add(table.getTableHeader(), BorderLayout.NORTH);
        paramsTable.add(table, BorderLayout.CENTER);

        JPanel paramsPanel = new JPanel(new BorderLayout());
        paramsPanel.setBorder(new TitledBorder(Translations.getString("PartDbDetailsPanel.parametersPanel.border")));
        paramsPanel.add(paramsTable, BorderLayout.CENTER);
        paramsPanel.add(kicadRow, BorderLayout.SOUTH);

        // --- Lots table ---
        lotsTableModel = new LotsTableModel();
        lotsTable = new JTable(lotsTableModel);
        lotsTable.setRowSelectionAllowed(false);
        lotsTable.setRowHeight(22);
        lotsTable.getColumnModel().getColumn(0).setPreferredWidth(30);   // Active
        lotsTable.getColumnModel().getColumn(0).setMaxWidth(30);
        lotsTable.getColumnModel().getColumn(1).setPreferredWidth(80);   // ID
        lotsTable.getColumnModel().getColumn(1).setMaxWidth(100);
        lotsTable.getColumnModel().getColumn(2).setPreferredWidth(180);  // Description
        lotsTable.getColumnModel().getColumn(3).setPreferredWidth(150);  // Storage Location
        lotsTable.getColumnModel().getColumn(4).setPreferredWidth(80);   // Amount
        lotsTable.getColumnModel().getColumn(4).setMaxWidth(100);

        qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));  // integers only
        ((JSpinner.DefaultEditor) qtySpinner.getEditor()).getTextField().setColumns(4);
        removeStockBtn = new JButton(Icons.delete);
        removeStockBtn.setEnabled(false);
        removeStockBtn.setToolTipText("Remove from active lot");
        removeStockBtn.addActionListener(e -> doAdjustStock(-1));
        addStockBtn = new JButton(Icons.add);
        addStockBtn.setEnabled(false);
        addStockBtn.setToolTipText("Add to active lot");
        addStockBtn.addActionListener(e -> doAdjustStock(+1));

        JPanel adjustPanel = new JPanel(new BorderLayout(4, 0));
        adjustPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JPanel adjustRight = new JPanel();
        adjustRight.add(new JLabel(Translations.getString("PartDbDetailsPanel.changeStockLabel.text")));
        adjustRight.add(qtySpinner);
        adjustRight.add(addStockBtn);
        adjustRight.add(removeStockBtn);
        adjustPanel.add(adjustRight, BorderLayout.EAST);

        JPanel lotsTable = new JPanel(new BorderLayout());
        lotsTable.add(this.lotsTable.getTableHeader(), BorderLayout.NORTH);
        lotsTable.add(this.lotsTable, BorderLayout.CENTER);

        JPanel lotsPanel = new JPanel(new BorderLayout());
        lotsPanel.setBorder(new TitledBorder(Translations.getString("PartDbDetailsPanel.stockLotsPanel.border")));
        lotsPanel.add(lotsTable, BorderLayout.CENTER);
        lotsPanel.add(adjustPanel, BorderLayout.SOUTH);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(lotsPanel, BorderLayout.NORTH);
        centerPanel.add(paramsPanel, BorderLayout.CENTER);

        // --- Bottom bar ---
        statusLabel = new JLabel(" ");
        JButton refreshBtn = new JButton(Translations.getString("PartDbDetailsPanel.refreshButton.text"));
        refreshBtn.addActionListener(e -> load());

        datasheetBtn = new JButton(Translations.getString("PartDbDetailsPanel.datasheetButton.text"));
        datasheetBtn.setEnabled(false);
        datasheetBtn.addActionListener(e -> openDatasheet());

        viewInDbBtn = new JButton(Translations.getString("PartDbDetailsPanel.viewInDbButton.text"));
        viewInDbBtn.setEnabled(false);
        viewInDbBtn.addActionListener(e -> openInDb());

        JPanel rightBtns = new JPanel();  // default FlowLayout
        rightBtns.add(datasheetBtn);
        rightBtns.add(viewInDbBtn);
        rightBtns.add(refreshBtn);

        JPanel bottomBar = new JPanel(new BorderLayout(4, 0));
        bottomBar.add(statusLabel, BorderLayout.CENTER);
        bottomBar.add(rightBtns, BorderLayout.EAST);

        JPanel scrollContent = new JPanel(new BorderLayout(0, 4));
        scrollContent.add(headerPanel, BorderLayout.NORTH);
        scrollContent.add(centerPanel, BorderLayout.CENTER);
        add(new JScrollPane(scrollContent,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER), BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
    }

    public void loadIfNeeded() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    private void load() {
        if (!db.isConnected()) {
            statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.notConnected"));
            return;
        }
        statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.loading"));
        rawData = null;
        datasheetBtn.setEnabled(false);
        viewInDbBtn.setEnabled(false);
        addStockBtn.setEnabled(false);
        removeStockBtn.setEnabled(false);
        applyPadsBtn.setEnabled(false);
        kicadFootprintField.setText("");
        tableModel.fireTableDataChanged();
        lotsTableModel.fireTableDataChanged();
        clearHeader();

        new SwingWorker<PartDbRawData, Void>() {
            private byte[] imgBytes;

            @Override
            protected PartDbRawData doInBackground() throws Exception {
                PartDbRawData d = db.fetchRawData(part.getId());
                if (d.thumbnailUrl != null) {
                    try {
                        imgBytes = db.requestBytes(d.thumbnailUrl);
                    } catch (Exception e) {
                        Logger.debug("PartDB: could not fetch thumbnail: {}", e.getMessage());
                    }
                }
                return d;
            }

            @Override
            protected void done() {
                try {
                    rawData = get();
                    populateHeader(rawData, imgBytes);
                    tableModel.fireTableDataChanged();
                    lotsTableModel.fireTableDataChanged();
                    preselectLot();
                    datasheetBtn.setEnabled(rawData.datasheetUrl != null);
                    viewInDbBtn.setEnabled(true);
                    boolean hasLots = !rawData.lots.isEmpty();
                    addStockBtn.setEnabled(hasLots);
                    removeStockBtn.setEnabled(hasLots);
                    String kicad = rawData.kicadFromPart != null ? rawData.kicadFromPart
                            : rawData.kicadFromFp;
                    currentKicadRef = kicad;
                    kicadFootprintField.setText(kicad != null ? kicad : "");
                    applyPadsBtn.setEnabled(kicad != null && !kicad.isEmpty()
                            && part.getPackage() != null);
                    statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.loaded"));
                } catch (InterruptedException | ExecutionException e) {
                    datasheetBtn.setEnabled(false);
                    viewInDbBtn.setEnabled(false);
                    statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.notFound"));
                    Logger.debug("PartDB details: {}", e.getMessage());
                }
            }
        }.execute();
    }

    private void doAdjustStock(int sign) {
        if (rawData == null || rawData.lots.isEmpty()) {
            return;
        }
        int lotId = db.getSelectedLotId(part.getId());
        if (lotId < 0) {
            return;
        }
        int delta = sign * (Integer) qtySpinner.getValue();
        addStockBtn.setEnabled(false);
        removeStockBtn.setEnabled(false);
        statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.updatingStock"));

        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return db.adjustLotAmount(part.getId(), lotId, delta);
            }
            @Override
            protected void done() {
                try {
                    int newAmount = get();
                    for (int i = 0; i < rawData.lots.size(); i++) {
                        if (rawData.lots.get(i).id == lotId) {
                            PartDbLot old = rawData.lots.get(i);
                            rawData.lots.set(i, new PartDbLot(old.id, old.description,
                                    old.storageLocation, newAmount));
                            break;
                        }
                    }
                    lotsTableModel.fireTableDataChanged();
                    statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.stockUpdated"));
                } catch (Exception e) {
                    statusLabel.setText(String.format(Translations.getString("PartDbDetailsPanel.status.updateFailed"), e.getCause().getMessage()));
                    Logger.warn("PartDB: stock update failed: {}", e.getMessage());
                } finally {
                    addStockBtn.setEnabled(true);
                    removeStockBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void preselectLot() {
        if (rawData == null || rawData.lots.isEmpty()) {
            return;
        }
        int stored = db.getSelectedLotId(part.getId());
        boolean found = rawData.lots.stream().anyMatch(l -> l.id == stored);
        if (!found) {
            // Auto-select first lot
            db.setSelectedLotId(part.getId(), rawData.lots.get(0).id);
            lotsTableModel.fireTableDataChanged();
        }
    }

    private void openDatasheet() {
        if (rawData == null || rawData.datasheetUrl == null) {
            return;
        }
        try {
            String dsUrl = rawData.datasheetUrl;
            URI uri;
            if (dsUrl.startsWith("http://") || dsUrl.startsWith("https://")) {
                uri = URI.create(dsUrl);
            } else {
                // Internal path — resolve against base URL
                String base = db.getUrl().replaceAll("/+$", "");
                uri = URI.create(base + dsUrl);
            }
            Desktop.getDesktop().browse(uri);
        } catch (Exception e) {
            Logger.warn("PartDB: could not open datasheet: {}", e.getMessage());
        }
    }

    private void openInDb() {
        if (rawData == null) {
            return;
        }
        try {
            String base = db.getUrl().replaceAll("/+$", "");
            Desktop.getDesktop().browse(URI.create(base + "/en/part/" + rawData.partDbId + "/info"));
        } catch (Exception e) {
            Logger.warn("PartDB: could not open part page: {}", e.getMessage());
        }
    }

    private KicadLibrary getKicadLibrary() {
        try {
            return ((ReferenceMachine) Configuration.get().getMachine()).getKicadLibrary();
        }
        catch (Exception e) {
            return null;
        }
    }

    private void applyKicadPads() {
        if (rawData == null || part.getPackage() == null) {
            return;
        }
        String kicad = rawData.kicadFromPart != null ? rawData.kicadFromPart : rawData.kicadFromFp;
        if (kicad == null || kicad.isEmpty()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "<html>Applying KiCad pads will overwrite the current footprint pads for package <b>"
                        + part.getPackage().getId() + "</b>.<br>"
                        + "It is not recommended to overwrite a footprint that was set manually.<br><br>"
                        + "Continue?</html>",
                Translations.getString("PartDbDetailsPanel.applyPadsDialog.title"),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        KicadLibrary kl = getKicadLibrary();
        boolean ok = kl != null && kl.importKicadPads(part.getPackage(), kicad);
        if (ok) {
            statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.padsApplied"));
        } else if (kl == null || !kl.isConfigured()) {
            statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.libraryNotConfigured"));
        } else {
            statusLabel.setText(String.format(Translations.getString("PartDbDetailsPanel.status.footprintNotFound"), kicad));
        }
    }

    private void selectKicadFootprint() {
        KicadLibrary kl = getKicadLibrary();
        KicadHttpLibraryDialog dlg = new KicadHttpLibraryDialog(MainFrame.get(), kl);
        dlg.setVisible(true);

        if (dlg.getSelectedRef() != null) {
            String ref = dlg.getSelectedRef();
            if (kl == null || !kl.isConfigured()) {
                statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.libraryNotConfigured"));
                return;
            }
            org.openpnp.model.Package pkg = ensurePackage(ref);
            if (pkg == null) {
                return;
            }
            boolean ok = kl.importKicadPads(pkg, ref);
            if (ok) {
                currentKicadRef = ref;
                kicadFootprintField.setText(ref);
                applyPadsBtn.setEnabled(true);
                db.setPendingKicadFootprint(part.getId(), ref);
                statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.footprintSelected"));
            } else {
                statusLabel.setText(String.format(Translations.getString("PartDbDetailsPanel.status.footprintNotFound"), ref));
            }
        } else if (dlg.getSelectedFile() != null) {
            try {
                File f = dlg.getSelectedFile();
                String nameHint = f.getName();
                if (nameHint.toLowerCase().endsWith(".kicad_mod")) {
                    nameHint = nameHint.substring(0, nameHint.length() - ".kicad_mod".length());
                }
                org.openpnp.model.Package pkg = ensurePackage(nameHint);
                if (pkg == null) {
                    return;
                }
                List<Footprint.Pad> pads = new KicadModImporter(f).getPads();
                Footprint fp = pkg.getFootprint();
                fp.getPads().clear();
                for (Footprint.Pad pad : pads) {
                    fp.addPad(pad);
                }
                statusLabel.setText(Translations.getString("PartDbDetailsPanel.status.padsFromFile"));
            } catch (Exception ex) {
                statusLabel.setText(String.format(Translations.getString("PartDbDetailsPanel.status.padsFromFileFailed"), ex.getMessage()));
                Logger.warn("PartDB: failed to import pads from file: {}", ex.getMessage());
            }
        }
        // cancelled: do nothing
    }

    /**
     * Returns the part's current package, or creates and assigns a new one derived from the given
     * footprint name hint (e.g. "Resistors:R_0603" → package id "R_0603").
     */
    private org.openpnp.model.Package ensurePackage(String nameHint) {
        if (part.getPackage() != null) {
            return part.getPackage();
        }
        // Derive package ID: use the portion after the last ':' if present
        String pkgId = nameHint.contains(":")
                ? nameHint.substring(nameHint.lastIndexOf(':') + 1)
                : nameHint;
        // Fall back to existing package with same ID if already registered
        org.openpnp.model.Package existing = Configuration.get().getPackage(pkgId);
        if (existing != null) {
            part.setPackage(existing);
            return existing;
        }
        org.openpnp.model.Package pkg = new org.openpnp.model.Package(pkgId);
        Configuration.get().addPackage(pkg);
        part.setPackage(pkg);
        return pkg;
    }

    private void rescaleImage() {
        if (imgOriginal == null) {
            imgLabel.setIcon(null);
            return;
        }
        int s = imgWrapper.getHeight() > 0 ? imgWrapper.getHeight() : IMG_SIZE;
        Image scaled = imgOriginal.getScaledInstance(s, s, Image.SCALE_SMOOTH);
        imgLabel.setIcon(new ImageIcon(scaled));
    }

    private void clearHeader() {
        imgOriginal = null;
        imgLabel.setIcon(null);
        nameLabel.setText(" ");
        idLabel.setText(" ");
        mpnLabel.setText(" ");
        ipnLabel.setText(" ");
        pkgLabel.setText(" ");
        descLabel.setText(" ");
    }

    private void populateHeader(PartDbRawData d, byte[] imgBytes) {
        nameLabel.setText(d.partName != null ? d.partName : " ");
        idLabel.setText("(ID: " + d.partDbId + ")");
        mpnLabel.setText(d.mpn != null ? "MPN: " + d.mpn : " ");
        ipnLabel.setText(d.ipn != null ? "IPN: " + d.ipn : " ");
        pkgLabel.setText(d.packageName != null ? "Package: " + d.packageName : " ");
        descLabel.setText(d.description != null ? "Description: " + d.description : " ");

        imgOriginal = null;
        if (imgBytes != null) {
            try {
                imgOriginal = ImageIO.read(new ByteArrayInputStream(imgBytes));
            } catch (Exception e) {
                Logger.debug("PartDB: could not decode thumbnail: {}", e.getMessage());
            }
        }
        rescaleImage();
    }

    // -------------------------------------------------------------------------
    // Value helpers
    // -------------------------------------------------------------------------

    private String getLocalValue(int row) {
        switch (row) {
            case ROW_HEIGHT:
                if (part.getHeight() == null) {
                    return "";
                }
                Length h = part.getHeight().convertToUnits(LengthUnit.Millimeters);
                return h.getValue() > 0 ? fmt(h.getValue()) : "";
            case ROW_BODY_WIDTH:
                if (part.getPackage() == null) {
                    return "";
                }
                double bw = part.getPackage().getFootprint().getBodyWidth();
                return bw > 0 ? fmt(bw) : "";
            case ROW_BODY_LENGTH:
                if (part.getPackage() == null) {
                    return "";
                }
                double bl = part.getPackage().getFootprint().getBodyHeight();
                return bl > 0 ? fmt(bl) : "";
            default:
                return "";
        }
    }

    private String getPartValue(int row) {
        if (rawData == null) {
            return "";
        }
        switch (row) {
            case ROW_HEIGHT:      return fmm(rawData.partHeight);
            case ROW_BODY_WIDTH:  return fmm(rawData.partBodyWidth);
            case ROW_BODY_LENGTH: return fmm(rawData.partBodyLength);
            default:              return "";
        }
    }

    private String getFpValue(int row) {
        if (rawData == null) {
            return "";
        }
        switch (row) {
            case ROW_HEIGHT:      return fmm(rawData.fpHeight);
            case ROW_BODY_WIDTH:  return fmm(rawData.fpBodyWidth);
            case ROW_BODY_LENGTH: return fmm(rawData.fpBodyLength);
            default:              return "";
        }
    }

    private String getResolvedValue(int row) {
        String pv = getPartValue(row);
        return (!pv.isEmpty()) ? pv : getFpValue(row);
    }

    private static String str(String s) {
        return (s != null && !s.isEmpty()) ? s : "";
    }

    private static String fmm(Double v) {
        return (v != null && v > 0) ? fmt(v) : "";
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    // -------------------------------------------------------------------------
    // Lots table model
    // -------------------------------------------------------------------------

    private class LotsTableModel extends AbstractTableModel {
        @Override public int getRowCount()    { return rawData == null ? 1 : rawData.lots.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int col) {
            switch (col) {
                case 0: return "";             // Active (checkbox)
                case 1: return "ID";
                case 2: return "Description";
                case 3: return "Storage Location";
                case 4: return "Amount";
                default: return "";
            }
        }
        @Override public Class<?> getColumnClass(int col) {
            switch (col) {
                case 0: return Boolean.class;
                case 1: return Integer.class;
                case 4: return Integer.class;
                default: return String.class;
            }
        }
        @Override public boolean isCellEditable(int row, int col) { return col == 0; }
        @Override public Object getValueAt(int row, int col) {
            if (rawData == null) {
                return col == 0 ? Boolean.FALSE : null;
            }
            PartDbLot lot = rawData.lots.get(row);
            switch (col) {
                case 0: return lot.id == db.getSelectedLotId(part.getId());
                case 1: return lot.id;
                case 2: return lot.description;
                case 3: return lot.storageLocation;
                case 4: return lot.amount;
                default: return "";
            }
        }
        @Override public void setValueAt(Object value, int row, int col) {
            if (col == 0 && Boolean.TRUE.equals(value) && rawData != null) {
                db.setSelectedLotId(part.getId(), rawData.lots.get(row).id);
                fireTableDataChanged();  // Refresh all checkboxes (only one active)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private class DetailsTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return ROW_COUNT;
        }
        @Override
        public int getColumnCount() {
            return 4;
        }
        @Override
        public String getColumnName(int col) {
            switch (col) {
                case 0: return "Property";
                case 1: return "Local";
                case 2: return "Remote \u2013 Part";
                case 3: return "Remote \u2013 Footprint";
                default: return "";
            }
        }
        @Override
        public Object getValueAt(int row, int col) {
            switch (col) {
                case 0: return ROW_NAMES[row];
                case 1: return getLocalValue(row);
                case 2: return getPartValue(row);
                case 3: return getFpValue(row);
                default: return "";
            }
        }
        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 1 && row <= ROW_BODY_LENGTH;
        }
        @Override
        public void setValueAt(Object aValue, int row, int col) {
            if (col != 1) {
                return;
            }
            String s = aValue.toString().trim();
            try {
                switch (row) {
                    case ROW_HEIGHT:
                        part.setHeight(new Length(Double.parseDouble(s), LengthUnit.Millimeters));
                        break;
                    case ROW_BODY_WIDTH:
                        if (part.getPackage() != null) {
                            part.getPackage().getFootprint().setBodyWidth(Double.parseDouble(s));
                        }
                        break;
                    case ROW_BODY_LENGTH:
                        if (part.getPackage() != null) {
                            part.getPackage().getFootprint().setBodyHeight(Double.parseDouble(s));
                        }
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException e) {
                // ignore invalid input
            }
            fireTableCellUpdated(row, col);
        }
    }

    // -------------------------------------------------------------------------
    // Cell renderer
    // -------------------------------------------------------------------------

    private class ConflictRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Color amber  = new Color(255, 215, 100);
        private final Color differ = new Color(255, 185, 185);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            setHorizontalAlignment(col == 0 ? SwingConstants.LEFT : SwingConstants.CENTER);
            if (!isSelected) {
                setBackground(table.getBackground());
                if (rawData != null) {
                    String local    = getLocalValue(row);
                    String resolved = getResolvedValue(row);
                    String partVal  = getPartValue(row);
                    String fpVal    = getFpValue(row);

                    if (col == 1 && !local.isEmpty() && !resolved.isEmpty()
                            && !local.equals(resolved)) {
                        setBackground(amber);
                    }
                    if ((col == 2 || col == 3) && !partVal.isEmpty() && !fpVal.isEmpty()
                            && !partVal.equals(fpVal)) {
                        setBackground(differ);
                    }
                }
            }
            return this;
        }
    }
}
