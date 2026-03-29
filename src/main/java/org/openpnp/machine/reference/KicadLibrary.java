package org.openpnp.machine.reference;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Action;
import javax.swing.Icon;

import org.openpnp.gui.importer.KicadModImporter;
import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.wizards.KicadLibraryWizard;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Footprint;
import org.openpnp.model.Package;
import org.openpnp.spi.PropertySheetHolder;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.core.Commit;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Standalone KiCad footprint library integration.
 *
 * Resolves KiCad footprint references ("Lib:FP") to pad lists by searching configured
 * library paths (local filesystem and/or HTTP bases).  An optional footprints.txt URL
 * provides a complete enumeration of available references for the browser dialog.
 *
 * This class is independent of PartDB — it can be used without any PartDB connection.
 */
@Root
public class KicadLibrary extends AbstractModelObject implements PropertySheetHolder {

    private final transient HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** In-RAM cache for fetched .kicad_mod content: URL → text. Never persisted. */
    private final transient Map<String, String> kicadUrlCache = new ConcurrentHashMap<>();

    /** Legacy absorber — old @Attribute storage; newlines were lost to XML normalisation. */
    @Attribute(name = "kicadLibraryPath", required = false)
    private String kicadLibraryPathAttr = null;

    /** Library paths, one per line.  Stored as XML element so newlines survive the round-trip. */
    @Element(required = false)
    private String kicadLibraryPath = "";

    public String getKicadLibraryPath() {
        return kicadLibraryPath;
    }

    public void setKicadLibraryPath(String path) {
        Object old = this.kicadLibraryPath;
        this.kicadLibraryPath = path;
        firePropertyChange("kicadLibraryPath", old, path);
    }

    /** Optional URL or file path to a footprints.txt listing all available KiCad footprints. */
    @Attribute(required = false)
    private String kicadFootprintListUrl = "";

    public String getKicadFootprintListUrl() {
        return kicadFootprintListUrl;
    }

    public void setKicadFootprintListUrl(String v) {
        String old = this.kicadFootprintListUrl;
        this.kicadFootprintListUrl = v;
        firePropertyChange("kicadFootprintListUrl", old, v);
    }

    /** Returns true if at least one library source is configured. */
    public boolean isConfigured() {
        return (kicadLibraryPath != null && !kicadLibraryPath.isEmpty())
                || (kicadFootprintListUrl != null && !kicadFootprintListUrl.isEmpty());
    }

    @Commit
    private void onLoad() {
        // Migrate from old @Attribute storage (newlines were lost to XML normalisation).
        if ((kicadLibraryPath == null || kicadLibraryPath.isEmpty())
                && kicadLibraryPathAttr != null && !kicadLibraryPathAttr.isEmpty()) {
            kicadLibraryPath = kicadLibraryPathAttr.trim();
        }
        kicadLibraryPathAttr = null;
    }

    // -------------------------------------------------------------------------
    // Footprint resolution
    // -------------------------------------------------------------------------

    /** Returns true if pads were successfully imported into {@code pkg}, false otherwise. */
    public boolean importKicadPads(Package pkg, String kicadFootprint) {
        if (kicadLibraryPath == null || kicadLibraryPath.isEmpty() || kicadFootprint == null) {
            return false;
        }
        if (!kicadFootprint.contains(":")) {
            Logger.debug("KiCad: footprint '{}' has no library prefix, skipping pad import", kicadFootprint);
            return false;
        }
        String[] parts = kicadFootprint.split(":", 2);
        String libName = parts[0];
        String fpName  = parts[1];
        for (String base : kicadLibraryPath.split("[\\r\\n]+")) {
            base = base.trim();
            if (base.isEmpty()) {
                continue;
            }

            // ── HTTP base URL ─────────────────────────────────────────────────
            if (base.startsWith("http://") || base.startsWith("https://")) {
                String normalizedBase = base.replaceAll("/+$", "");
                String fileUrl;
                if (normalizedBase.endsWith(".pretty")) {
                    String dirLib = normalizedBase.substring(normalizedBase.lastIndexOf('/') + 1)
                                                  .replace(".pretty", "");
                    if (!dirLib.equals(libName)) {
                        continue;
                    }
                    fileUrl = normalizedBase + "/" + fpName + ".kicad_mod";
                } else {
                    fileUrl = normalizedBase + "/" + libName + ".pretty/" + fpName + ".kicad_mod";
                }
                try {
                    String content = fetchKicadUrl(fileUrl);
                    if (content == null) {
                        continue; // 404 — try next base URL
                    }
                    List<Footprint.Pad> pads = new KicadModImporter(content).getPads();
                    Footprint fp = pkg.getFootprint();
                    fp.getPads().clear();
                    for (Footprint.Pad pad : pads) {
                        fp.addPad(pad);
                    }
                    Logger.info("KiCad: imported {} pad(s) from '{}'", pads.size(), fileUrl);
                    return true;
                } catch (Exception e) {
                    Logger.warn("KiCad: could not import pads from '{}': {}", fileUrl, e.getMessage());
                    return false;
                }
            }

            // ── Filesystem path ───────────────────────────────────────────────
            File kicadFile;
            if (base.endsWith(".pretty")) {
                String dirLib = new File(base).getName().replace(".pretty", "");
                if (!dirLib.equals(libName)) {
                    continue;
                }
                kicadFile = new File(base, fpName + ".kicad_mod");
            } else {
                kicadFile = new File(base, libName + ".pretty/" + fpName + ".kicad_mod");
            }
            if (!kicadFile.exists()) {
                continue;
            }
            try {
                List<Footprint.Pad> pads = new KicadModImporter(kicadFile).getPads();
                Footprint fp = pkg.getFootprint();
                fp.getPads().clear();
                for (Footprint.Pad pad : pads) {
                    fp.addPad(pad);
                }
                Logger.info("KiCad: imported {} pad(s) from '{}'", pads.size(), kicadFile.getName());
                return true;
            } catch (Exception e) {
                Logger.warn("KiCad: could not import pads from '{}': {}", kicadFile, e.getMessage());
                return false;
            }
        }
        Logger.debug("KiCad: footprint '{}' not found in any library path", kicadFootprint);
        return false;
    }

    /** Resolves a KiCad footprint reference to a list of pads. Throws if not found. */
    public List<Footprint.Pad> resolveKicadFootprintPads(String kicadFootprint) throws Exception {
        if (kicadLibraryPath == null || kicadLibraryPath.isEmpty()) {
            throw new Exception("No KiCad library path configured.");
        }
        if (kicadFootprint == null || !kicadFootprint.contains(":")) {
            throw new Exception("Invalid KiCad footprint reference: " + kicadFootprint);
        }
        String[] parts = kicadFootprint.split(":", 2);
        String libName = parts[0];
        String fpName  = parts[1];
        for (String base : kicadLibraryPath.split("[\\r\\n]+")) {
            base = base.trim();
            if (base.isEmpty()) {
                continue;
            }
            if (base.startsWith("http://") || base.startsWith("https://")) {
                String normalizedBase = base.replaceAll("/+$", "");
                String fileUrl;
                if (normalizedBase.endsWith(".pretty")) {
                    String dirLib = normalizedBase.substring(normalizedBase.lastIndexOf('/') + 1)
                            .replace(".pretty", "");
                    if (!dirLib.equals(libName)) {
                        continue;
                    }
                    fileUrl = normalizedBase + "/" + fpName + ".kicad_mod";
                } else {
                    fileUrl = normalizedBase + "/" + libName + ".pretty/" + fpName + ".kicad_mod";
                }
                String content = fetchKicadUrl(fileUrl);
                if (content != null) {
                    return new KicadModImporter(content).getPads();
                }
                continue;
            }
            File kicadFile;
            if (base.endsWith(".pretty")) {
                String dirLib = new File(base).getName().replace(".pretty", "");
                if (!dirLib.equals(libName)) {
                    continue;
                }
                kicadFile = new File(base, fpName + ".kicad_mod");
            } else {
                kicadFile = new File(base, libName + ".pretty/" + fpName + ".kicad_mod");
            }
            if (!kicadFile.exists()) {
                continue;
            }
            return new KicadModImporter(kicadFile).getPads();
        }
        throw new Exception("Footprint '" + kicadFootprint + "' not found in any configured library path.");
    }

    /**
     * Builds a sorted, deduplicated list of all available KiCad footprint references from:
     * (1) local {@code .pretty} directories in {@code kicadLibraryPath}, and
     * (2) an optional {@code kicadFootprintListUrl} (one {@code Lib:FP} per line).
     */
    public List<String> loadKicadFootprintList() throws Exception {
        TreeSet<String> result = new TreeSet<>();

        // --- Enumerate local .pretty directories --------------------------------
        if (kicadLibraryPath != null && !kicadLibraryPath.isEmpty()) {
            for (String base : kicadLibraryPath.split("[\\r\\n]+")) {
                base = base.trim();
                if (base.isEmpty() || base.startsWith("http://") || base.startsWith("https://")) {
                    continue;
                }
                File baseDir = new File(base);
                if (base.endsWith(".pretty")) {
                    String libName = baseDir.getName().replace(".pretty", "");
                    File[] mods = baseDir.listFiles(f -> f.getName().endsWith(".kicad_mod"));
                    if (mods != null) {
                        for (File mod : mods) {
                            result.add(libName + ":" + mod.getName().replace(".kicad_mod", ""));
                        }
                    }
                } else {
                    File[] prettyDirs = baseDir.listFiles(
                            f -> f.isDirectory() && f.getName().endsWith(".pretty"));
                    if (prettyDirs != null) {
                        for (File pretty : prettyDirs) {
                            String libName = pretty.getName().replace(".pretty", "");
                            File[] mods = pretty.listFiles(f -> f.getName().endsWith(".kicad_mod"));
                            if (mods != null) {
                                for (File mod : mods) {
                                    result.add(libName + ":" + mod.getName().replace(".kicad_mod", ""));
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- Load footprints.txt from URL or file path --------------------------
        if (kicadFootprintListUrl != null && !kicadFootprintListUrl.isEmpty()) {
            String text;
            if (kicadFootprintListUrl.startsWith("http://")
                    || kicadFootprintListUrl.startsWith("https://")) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(kicadFootprintListUrl))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> resp = httpClient.send(req,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                    throw new IOException("HTTP " + resp.statusCode()
                            + " fetching " + kicadFootprintListUrl);
                }
                text = resp.body();
            } else {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(kicadFootprintListUrl))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
                text = sb.toString();
            }
            for (String line : text.split("[\\r\\n]+")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#") && line.contains(":")) {
                    result.add(line);
                }
            }
        }

        return new ArrayList<>(result);
    }

    /**
     * Fetches .kicad_mod content from an HTTP URL.
     * Returns null on 404; results are cached for the session.
     */
    private String fetchKicadUrl(String url) throws Exception {
        String cached = kicadUrlCache.get(url);
        if (cached != null) {
            Logger.debug("KiCad cache hit: {}", url);
            return cached;
        }
        Logger.debug("KiCad fetching: {}", url);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching " + url);
        }
        String content = resp.body();
        kicadUrlCache.put(url, content);
        return content;
    }

    // -------------------------------------------------------------------------
    // PropertySheetHolder
    // -------------------------------------------------------------------------

    public Wizard getConfigurationWizard() {
        return new KicadLibraryWizard(this);
    }

    @Override
    public String getPropertySheetHolderTitle() {
        return "KiCad Libraries";
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {
            new org.openpnp.gui.support.PropertySheetWizardAdapter(getConfigurationWizard())
        };
    }

    @Override
    public Action[] getPropertySheetHolderActions() {
        return null;
    }

    @Override
    public Icon getPropertySheetHolderIcon() {
        return null;
    }
}
