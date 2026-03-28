package org.openpnp.machine.reference;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;


import javax.swing.SwingUtilities;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.openpnp.gui.importer.KicadModImporter;
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.wizards.PartDbDatabaseWizard;
import org.openpnp.model.Footprint;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.base.AbstractPartDatabase;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Attribute;
import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementMap;
import org.simpleframework.xml.Root;
import org.simpleframework.xml.core.Commit;

/**
 * PartDB integration (https://docs.part-db.de/api/).
 *
 * Authentication: Bearer token created in PartDB user settings.
 * The PartDB part name is used as the OpenPnP Part ID.
 */
@Root
public class PartDbDatabase extends AbstractPartDatabase {

    private final transient HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Attribute(required = false)
    private boolean enabled = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) {
        boolean old = this.enabled;
        this.enabled = v;
        firePropertyChange("enabled", old, v);
    }

    @Attribute(required = false)
    private boolean trackPlacements = false;

    public boolean isTrackPlacements() { return trackPlacements; }
    public void setTrackPlacements(boolean v) {
        boolean old = this.trackPlacements;
        this.trackPlacements = v;
        firePropertyChange("trackPlacements", old, v);
    }

    @Attribute(required = false)
    private boolean disableAutoFlushOnShutdown = false;

    public boolean isDisableAutoFlushOnShutdown() { return disableAutoFlushOnShutdown; }
    public void setDisableAutoFlushOnShutdown(boolean v) {
        boolean old = this.disableAutoFlushOnShutdown;
        this.disableAutoFlushOnShutdown = v;
        firePropertyChange("disableAutoFlushOnShutdown", old, v);
    }

    @Attribute(required = false)
    private boolean readOnly = false;

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean v) {
        boolean old = this.readOnly;
        this.readOnly = v;
        firePropertyChange("readOnly", old, v);
    }

    @Attribute(required = false)
    private boolean autoApplyKicadPads = false;

    public boolean isAutoApplyKicadPads() { return autoApplyKicadPads; }
    public void setAutoApplyKicadPads(boolean v) {
        boolean old = this.autoApplyKicadPads;
        this.autoApplyKicadPads = v;
        firePropertyChange("autoApplyKicadPads", old, v);
    }

    @Attribute(required = false)
    private boolean disableFootprintOnImport = false;

    public boolean isDisableFootprintOnImport() { return disableFootprintOnImport; }
    public void setDisableFootprintOnImport(boolean v) {
        boolean old = this.disableFootprintOnImport;
        this.disableFootprintOnImport = v;
        firePropertyChange("disableFootprintOnImport", old, v);
    }

    @Attribute(required = false)
    private boolean disableFootprintOnUpdate = false;

    public boolean isDisableFootprintOnUpdate() { return disableFootprintOnUpdate; }
    public void setDisableFootprintOnUpdate(boolean v) {
        boolean old = this.disableFootprintOnUpdate;
        this.disableFootprintOnUpdate = v;
        firePropertyChange("disableFootprintOnUpdate", old, v);
    }

    @Attribute(required = false)
    private boolean hideStockLevel = false;

    public boolean isHideStockLevel() { return hideStockLevel; }
    public void setHideStockLevel(boolean v) {
        boolean old = this.hideStockLevel;
        this.hideStockLevel = v;
        firePropertyChange("hideStockLevel", old, v);
    }

    /** Legacy absorber — old showStockLevel attribute; no longer used. */
    @Attribute(name = "showStockLevel", required = false)
    private boolean showStockLevelLegacy = false;

    /** Absorbs the old XML attribute so strict-mode deserialisation does not fail on existing configs. */
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

    /** Legacy absorber for old adjustStockOnJobFinish attribute. */
    @Attribute(name = "adjustStockOnJobFinish", required = false)
    private boolean adjustStockOnJobFinishLegacy = false;

    @Attribute(required = false)
    private boolean disableAutoFlushOnJobFinish = false;

    public boolean isDisableAutoFlushOnJobFinish() { return disableAutoFlushOnJobFinish; }
    public void setDisableAutoFlushOnJobFinish(boolean v) {
        boolean old = this.disableAutoFlushOnJobFinish;
        this.disableAutoFlushOnJobFinish = v;
        firePropertyChange("disableAutoFlushOnJobFinish", old, v);
    }

    @Attribute(required = false)
    private boolean allowExternalAttachments = false;

    /** Maps OpenPnP part ID → selected PartDB lot ID for stock adjustment. */
    @ElementMap(required = false, entry = "lot", key = "partId", value = "lotId", attribute = true)
    private Map<String, Integer> selectedLots = new HashMap<>();

    public int getSelectedLotId(String partId) {
        return selectedLots.getOrDefault(partId, -1);
    }

    public void setSelectedLotId(String partId, int lotId) {
        selectedLots.put(partId, lotId);
    }

    public void clearSelectedLotId(String partId) {
        selectedLots.remove(partId);
    }

    public boolean isAllowExternalAttachments() {
        return allowExternalAttachments;
    }

    public void setAllowExternalAttachments(boolean v) {
        boolean old = this.allowExternalAttachments;
        this.allowExternalAttachments = v;
        firePropertyChange("allowExternalAttachments", old, v);
    }

    /** Cached stock levels: partId → total amount across all lots. Absent = not fetched. */
    private final transient Map<String, Integer> stockCache = new HashMap<>();

    /** In-RAM cache: OpenPnP part name → PartDB numeric ID. Never persisted. */
    private final transient Map<String, Integer> partIdCache = new ConcurrentHashMap<>();

    /** In-RAM cache for fetched .kicad_mod content: URL → text. Never persisted. */
    private final transient Map<String, String> kicadUrlCache = new ConcurrentHashMap<>();

    /** Pending placement counts not yet flushed to PartDB: partId → count. */
    private final transient Map<String, Integer> pendingPlacements = new ConcurrentHashMap<>();

    /** Returns the cached stock level for a part, or null if not yet fetched. */
    public Integer getStockLevel(String partId) {
        return stockCache.get(partId);
    }

    /** Returns the number of pending (not yet flushed) placements for a part. */
    public int getPendingCount(String partId) {
        return pendingPlacements.getOrDefault(partId, 0);
    }

    /** Returns true if there are any pending placements waiting to be flushed. */
    public boolean hasPendingPlacements() {
        return !pendingPlacements.isEmpty();
    }

    /** -1 = idle, 0..N = refresh in progress (current index out of total). */
    private transient int stockRefreshProgress = -1;
    private transient int stockRefreshTotal = 0;

    public int getStockRefreshProgress() { return stockRefreshProgress; }
    public int getStockRefreshTotal()    { return stockRefreshTotal; }

    /** Fetches stock levels for all known OpenPnP parts from PartDB and fires "stockLevels". */
    public void refreshStockLevels() {
        if (!connected) {
            return;
        }
        List<Part> parts = new ArrayList<>(Configuration.get().getParts());
        stockRefreshTotal = parts.size();
        firePropertyChange("stockRefreshProgress", -1, 0);
        stockRefreshProgress = 0;
        for (Part part : parts) {
            try {
                JsonObject partData = findPartByName(part.getId());
                int stock = partData.get("total_instock").getAsBigDecimal().intValue();
                stockCache.put(part.getId(), stock);
            } catch (Exception e) {
                // Part not in PartDB or network error — skip silently.
            }
            int prev = stockRefreshProgress;
            stockRefreshProgress = prev + 1;
            firePropertyChange("stockRefreshProgress", prev, stockRefreshProgress);
        }
        stockRefreshProgress = -1;
        firePropertyChange("stockRefreshProgress", parts.size(), -1);
        firePropertyChange("stockLevels", null, stockCache);
    }

    @SuppressWarnings("deprecation")
    private static final JsonParser JSON_PARSER = new JsonParser();

    @Commit
    private void onLoad() {
        // Migrate from old @Attribute storage (newlines were lost to XML normalisation).
        if ((kicadLibraryPath == null || kicadLibraryPath.isEmpty())
                && kicadLibraryPathAttr != null && !kicadLibraryPathAttr.isEmpty()) {
            kicadLibraryPath = kicadLibraryPathAttr.trim();
        }
        kicadLibraryPathAttr = null;

        if (enabled && url != null && !url.isEmpty() && apiToken != null && !apiToken.isEmpty()) {
            Thread t = new Thread(() -> {
                try {
                    connect();
                    refreshStockLevels();
                } catch (Exception e) {
                    Logger.warn("PartDB auto-connect failed: {}", e.getMessage());
                }
            });
            t.setDaemon(true);
            t.setName("PartDB-connect");
            t.start();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (enabled && !disableAutoFlushOnShutdown && hasPendingPlacements()) {
                Logger.info("PartDB: flushing {} pending placement record(s) on shutdown",
                        pendingPlacements.size());
                try {
                    flushPlacements();
                } catch (Exception e) {
                    Logger.warn("PartDB: shutdown flush failed: {}", e.getMessage());
                }
            }
        }, "PartDB-shutdown-flush"));
    }

    @Override
    public Wizard getConfigurationWizard() {
        return new PartDbDatabaseWizard(this);
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    @Override
    public void connect() throws Exception {
        connected = false;
        partIdCache.clear();
        kicadUrlCache.clear();
        try {
            request("GET", "/api/tokens/current", null);
        } catch (Exception e) {
            firePropertyChange("connected", true, false);
            throw e;
        }
        connected = true;
        firePropertyChange("connected", false, true);
        Logger.info("PartDB connected to {}", url);
    }

    // -------------------------------------------------------------------------
    // Import / Update / Push
    // -------------------------------------------------------------------------

    @Override
    public List<String> searchParts(String query) throws Exception {
        // PartDB's name filter supports SQL LIKE wildcards: %25 = '%' (matches anything).
        // Wrap with % on both sides for a contains-search.
        String json = request("GET", "/api/parts/?name=%25" + urlEncode(query) + "%25", null);
        JsonArray results = parseArray(json);
        List<String> names = new ArrayList<>();
        for (JsonElement el : results) {
            names.add(el.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }

    @Override
    public void trackPlacement(String partId) throws Exception {
        if (!connected || !trackPlacements) {
            return;
        }
        pendingPlacements.merge(partId, 1, Integer::sum);
        firePropertyChange("pendingPlacements", null, pendingPlacements);
    }

    @Override
    public void onJobFinished() throws Exception {
        if (!disableAutoFlushOnJobFinish) {
            flushPlacements();
        }
    }

    @Override
    public void flushPlacements() throws Exception {
        if (!connected || pendingPlacements.isEmpty()) {
            return;
        }
        if (readOnly) {
            Logger.info("PartDB: read-only mode — flush skipped");
            return;
        }
        Map<String, Integer> toFlush = new HashMap<>(pendingPlacements);
        pendingPlacements.clear();
        for (Map.Entry<String, Integer> entry : toFlush.entrySet()) {
            try {
                adjustStock(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                Logger.warn("PartDB: could not flush stock for '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        // Refresh cache first so the table shows the new value and loses yellow simultaneously.
        refreshStockLevels();
        firePropertyChange("pendingPlacements", null, pendingPlacements);
        Logger.info("PartDB: flushed stock for {} part(s)", toFlush.size());
    }

    /** Adjusts the amount of a specific lot by delta (positive = add, negative = remove).
     *  Returns the new amount after clamping to 0. */
    public int adjustLotAmount(String partId, int lotId, int delta) throws Exception {
        if (readOnly) {
            throw new Exception("Read-only mode — stock writes are disabled");
        }
        JsonObject lot = parseObject(request("GET", "/api/part_lots/" + lotId, null));
        int current = lot.get("amount").getAsBigDecimal().intValue();
        int updated = Math.max(0, current + delta);
        JsonObject patch = new JsonObject();
        patch.addProperty("amount", updated);
        request("PATCH", "/api/part_lots/" + lotId, patch.toString());
        int actualDelta = updated - current;
        Integer oldStock = stockCache.get(partId);
        if (oldStock != null) {
            stockCache.put(partId, Math.max(0, oldStock + actualDelta));
        }
        firePropertyChange("stockLevels", null, stockCache);
        return updated;
    }

    private void adjustStock(String partName, int count) throws Exception {
        int lotId = selectedLots.getOrDefault(partName, -1);
        int current;
        if (lotId >= 0) {
            // Use the user-selected lot directly
            JsonObject lot = parseObject(request("GET", "/api/part_lots/" + lotId, null));
            current = lot.get("amount").getAsBigDecimal().intValue();
        } else {
            // Fall back to first available lot
            JsonObject partData = findPartByName(partName);
            int partDbId = partData.get("id").getAsInt();
            JsonArray lots = parseArray(request("GET",
                    "/api/part_lots/?part=/api/parts/" + partDbId, null));
            if (lots.size() == 0) {
                Logger.warn("PartDB: no lots for '{}', skipping stock adjustment", partName);
                return;
            }
            JsonObject lot = lots.get(0).getAsJsonObject();
            lotId = lot.get("id").getAsInt();
            current = lot.get("amount").getAsBigDecimal().intValue();
        }
        int updated = Math.max(0, current - count);
        JsonObject patch = new JsonObject();
        patch.addProperty("amount", updated);
        request("PATCH", "/api/part_lots/" + lotId, patch.toString());
        Logger.info("PartDB: stock '{}' {} → {} (placed {})", partName, current, updated, count);
    }

    @Override
    public Part importPart(String name) throws Exception {
        JsonObject partData = findPartByName(name);
        // Use the exact name from PartDB (preserves original casing) as the OpenPnP Part ID.
        String exactName = partData.get("name").getAsString();
        Part part = new Part(exactName);
        applyPartData(part, partData, false);
        // addPart fires property changes that Swing listens to — must run on the EDT.
        if (SwingUtilities.isEventDispatchThread()) {
            Configuration.get().addPart(part);
        } else {
            SwingUtilities.invokeAndWait(() -> Configuration.get().addPart(part));
        }
        Logger.info("PartDB: imported part '{}'", name);
        return part;
    }

    @Override
    public void updatePart(Part part) throws Exception {
        JsonObject partData = findPartByName(part.getId());
        applyPartData(part, partData, true);
        int stock = partData.get("total_instock").getAsBigDecimal().intValue();
        stockCache.put(part.getId(), stock);
        firePropertyChange("stockLevels", null, stockCache);
        Logger.info("PartDB: updated part '{}'", part.getId());
    }

    @Override
    public void pushPart(Part part) throws Exception {
        JsonObject existing = findPartByName(part.getId());
        int partDbId = existing.get("id").getAsInt();
        request("PATCH", "/api/parts/" + partDbId, buildPartJson(part));
        Logger.info("PartDB: updated part '{}' (id={})", part.getId(), partDbId);
        Length height = part.getHeight();
        double heightMm = (height != null) ? height.convertToUnits(LengthUnit.Millimeters).getValue() : 0;
        try {
            pushHeightParameter(partDbId, heightMm);
        } catch (Exception e) {
            Logger.warn("PartDB: could not push height parameter ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
        Integer pending = pendingPlacements.remove(part.getId());
        if (pending != null && pending > 0) {
            adjustStock(part.getId(), pending);
            firePropertyChange("pendingPlacements", null, pendingPlacements);
        }
        JsonObject refreshed = parseObject(request("GET", "/api/parts/" + partDbId, null));
        stockCache.put(part.getId(), refreshed.get("total_instock").getAsBigDecimal().intValue());
        firePropertyChange("stockLevels", null, stockCache);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Returns true if pads were successfully imported, false otherwise. */
    public boolean importKicadPads(Package pkg, String kicadFootprint) {
        if (kicadLibraryPath == null || kicadLibraryPath.isEmpty() || kicadFootprint == null) {
            return false;
        }
        if (!kicadFootprint.contains(":")) {
            Logger.debug("PartDB: kicad_footprint '{}' has no library prefix, skipping pad import", kicadFootprint);
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
                    Logger.info("PartDB: imported {} pad(s) from '{}'", pads.size(), fileUrl);
                    return true;
                } catch (Exception e) {
                    Logger.warn("PartDB: could not import KiCad pads from '{}': {}", fileUrl, e.getMessage());
                    return false;
                }
            }

            // ── Filesystem path ───────────────────────────────────────────────
            File kicadFile;
            if (base.endsWith(".pretty")) {
                // Each line points directly at a .pretty library directory.
                // Only search here if the directory name matches the library.
                String dirLib = new File(base).getName().replace(".pretty", "");
                if (!dirLib.equals(libName)) {
                    continue;
                }
                kicadFile = new File(base, fpName + ".kicad_mod");
            } else {
                // Each line is the parent directory containing .pretty subdirectories.
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
                Logger.info("PartDB: imported {} pad(s) from '{}'", pads.size(), kicadFile.getName());
                return true;
            } catch (Exception e) {
                Logger.warn("PartDB: could not import KiCad pads from '{}': {}", kicadFile, e.getMessage());
                return false;
            }
        }
        Logger.debug("PartDB: kicad_footprint '{}' not found in any library path", kicadFootprint);
        return false;
    }

    /**
     * Fetches text content of a .kicad_mod file from an HTTP URL.
     * Returns null on 404 (caller should try the next base URL).
     * Results are cached in kicadUrlCache for the session.
     */
    private String fetchKicadUrl(String url) throws Exception {
        String cached = kicadUrlCache.get(url);
        if (cached != null) {
            Logger.debug("PartDB KiCad cache hit: {}", url);
            return cached;
        }
        Logger.debug("PartDB KiCad fetching: {}", url);
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

    private JsonObject findPartByName(String name) throws Exception {
        Integer cachedId = partIdCache.get(name);
        if (cachedId != null) {
            return parseObject(request("GET", "/api/parts/" + cachedId, null));
        }
        String json = request("GET", "/api/parts/?name=" + urlEncode(name), null);
        JsonArray results = parseArray(json);
        if (results.size() == 0) {
            throw new Exception("PartDB: no part found with name '" + name + "'");
        }
        JsonObject part = results.get(0).getAsJsonObject();
        partIdCache.put(name, part.get("id").getAsInt());
        return part;
    }

/** A single stock lot from PartDB. */
    public static class PartDbLot {
        public final int id;
        public final String description;
        public final String storageLocation;
        public final int amount;

        public PartDbLot(int id, String description, String storageLocation, int amount) {
            this.id = id;
            this.description = description;
            this.storageLocation = storageLocation;
            this.amount = amount;
        }
    }

    /** Raw values fetched from PartDB for display in the Part Database details tab. */
    public static class PartDbRawData {
        // Header info
        public final int partDbId;
        public final String partName;
        public final String ipn;
        public final String mpn;
        public final String packageName;
        public final String description;
        public final String thumbnailUrl;   // relative path or absolute URL, null if none
        public final String datasheetUrl;   // absolute URL or relative path, null if none
        // Parameter columns
        public final String kicadFromPart;
        public final String kicadFromFp;
        public final Double partHeight;
        public final Double fpHeight;
        public final Double partBodyWidth;
        public final Double fpBodyWidth;
        public final Double partBodyLength;
        public final Double fpBodyLength;
        public final List<PartDbLot> lots;

        public PartDbRawData(int partDbId, String partName, String ipn, String mpn, String packageName,
                String description, String thumbnailUrl, String datasheetUrl,
                String kicadFromPart, String kicadFromFp,
                Double partHeight, Double fpHeight,
                Double partBodyWidth, Double fpBodyWidth,
                Double partBodyLength, Double fpBodyLength,
                List<PartDbLot> lots) {
            this.partDbId = partDbId;
            this.partName = partName;
            this.ipn = ipn;
            this.mpn = mpn;
            this.packageName = packageName;
            this.description = description;
            this.thumbnailUrl = thumbnailUrl;
            this.datasheetUrl = datasheetUrl;
            this.kicadFromPart = kicadFromPart;
            this.kicadFromFp = kicadFromFp;
            this.partHeight = partHeight;
            this.fpHeight = fpHeight;
            this.partBodyWidth = partBodyWidth;
            this.fpBodyWidth = fpBodyWidth;
            this.partBodyLength = partBodyLength;
            this.fpBodyLength = fpBodyLength;
            this.lots = lots != null ? lots : new ArrayList<>();
        }
    }

    /** Fetches all managed values from PartDB for the given part name, keeping part-level and
     *  footprint-level values separate so callers can detect conflicts. */
    public PartDbRawData fetchRawData(String partName) throws Exception {
        JsonObject detail = findPartByName(partName);
        int partId = detail.get("id").getAsInt();

        String dbPartName = detail.has("name") ? detail.get("name").getAsString() : null;

        String ipn = null;
        if (detail.has("ipn") && !detail.get("ipn").isJsonNull()) {
            ipn = detail.get("ipn").getAsString();
            if (ipn.isEmpty()) {
                ipn = null;
            }
        }

        String mpn = null;
        if (detail.has("manufacturer_product_number") && !detail.get("manufacturer_product_number").isJsonNull()) {
            mpn = detail.get("manufacturer_product_number").getAsString();
            if (mpn.isEmpty()) {
                mpn = dbPartName;  // API: use name if MPN is empty
            }
        }

        String description = null;
        if (detail.has("description") && !detail.get("description").isJsonNull()) {
            String d = detail.get("description").getAsString();
            if (!d.isEmpty()) {
                description = d;
            }
        }

        // Use internal_path for the picture (avoids the //host/cache/… protocol-relative thumbnail_url).
        // Fall back to external_path only when allowExternalAttachments is enabled.
        String thumbnailUrl = null;
        if (detail.has("master_picture_attachment") && !detail.get("master_picture_attachment").isJsonNull()) {
            thumbnailUrl = pickAttachmentUrl(detail.getAsJsonObject("master_picture_attachment"));
        }

        String datasheetUrl = null;
        if (detail.has("attachments") && detail.get("attachments").isJsonArray()) {
            for (JsonElement el : detail.getAsJsonArray("attachments")) {
                JsonObject att = el.getAsJsonObject();
                String attName = att.has("name") ? att.get("name").getAsString().toLowerCase() : "";
                String intPath = att.has("internal_path") ? att.get("internal_path").getAsString() : "";
                String extPath = (att.has("external_path") && !att.get("external_path").isJsonNull())
                        ? att.get("external_path").getAsString() : "";
                boolean isPdf = intPath.toLowerCase().endsWith(".pdf")
                        || extPath.toLowerCase().endsWith(".pdf");
                boolean isNamedDatasheet = attName.contains("datasheet");
                if (isPdf || isNamedDatasheet) {
                    datasheetUrl = pickAttachmentUrl(att);
                    if (datasheetUrl != null) {
                        break;
                    }
                }
            }
        }

        String packageName = null;
        int footprintId = -1;
        if (detail.has("footprint") && !detail.get("footprint").isJsonNull()) {
            JsonObject fp = detail.getAsJsonObject("footprint");
            if (fp.has("name")) {
                packageName = fp.get("name").getAsString();
            }
            if (fp.has("id")) {
                footprintId = fp.get("id").getAsInt();
            }
        }

        String kicadFromPart = extractKicadFootprint(detail);
        JsonArray partParams = detail.has("parameters") ? detail.getAsJsonArray("parameters") : null;
        Double partHeight = fetchParamValue(partParams, "height");
        Double partBodyWidth = fetchParamValue(partParams, "width");
        Double partBodyLength = fetchParamValue(partParams, "length");

        String kicadFromFp = null;
        Double fpHeight = null;
        Double fpBodyWidth = null;
        Double fpBodyLength = null;
        if (footprintId >= 0) {
            try {
                JsonObject fpDetail = parseObject(request("GET", "/api/footprints/" + footprintId, null));
                JsonArray fpParams = fpDetail.has("parameters") ? fpDetail.getAsJsonArray("parameters") : null;
                kicadFromFp = extractKicadFootprint(fpDetail);
                fpHeight = fetchParamValue(fpParams, "height");
                fpBodyWidth = fetchParamValue(fpParams, "width");
                fpBodyLength = fetchParamValue(fpParams, "length");
            } catch (Exception e) {
                Logger.debug("PartDB: could not fetch footprint detail for raw data: {}", e.getMessage());
            }
        }

        List<PartDbLot> lots = new ArrayList<>();
        if (detail.has("partLots") && detail.get("partLots").isJsonArray()) {
            for (JsonElement el : detail.getAsJsonArray("partLots")) {
                JsonObject lot = el.getAsJsonObject();
                int lotId = lot.get("id").getAsInt();
                String lotDesc = lot.has("description") ? lot.get("description").getAsString() : "";
                int amount = lot.has("amount") ? lot.get("amount").getAsBigDecimal().intValue() : 0;
                String storagePath = "";
                if (lot.has("storage_location") && !lot.get("storage_location").isJsonNull()
                        && lot.get("storage_location").isJsonObject()) {
                    JsonObject sl = lot.getAsJsonObject("storage_location");
                    String fp = sl.has("full_path") ? sl.get("full_path").getAsString() : "";
                    String nm = sl.has("name") ? sl.get("name").getAsString() : "";
                    storagePath = !fp.isEmpty() ? fp : nm;
                }
                lots.add(new PartDbLot(lotId, lotDesc, storagePath, amount));
            }
        }

        return new PartDbRawData(partId, dbPartName, ipn, mpn, packageName, description, thumbnailUrl, datasheetUrl,
                kicadFromPart, kicadFromFp,
                partHeight, fpHeight, partBodyWidth, fpBodyWidth, partBodyLength, fpBodyLength,
                lots);
    }

    /** Fetches raw bytes from an image URL. Absolute URLs (http/https) are used as-is;
     *  relative paths are resolved against the PartDB base URL with authentication.
     *  If a LiipImagineBundle cache path returns 404 the resolve URL is tried to warm the cache. */
    public byte[] requestBytes(String imageUrl) throws Exception {
        boolean absolute = imageUrl.startsWith("http://") || imageUrl.startsWith("https://");
        String fullUrl = absolute ? imageUrl : url.replaceAll("/+$", "") + imageUrl;
        HttpResponse<byte[]> resp = doGetBytes(fullUrl, !absolute);
        // LiipImagineBundle thumbnails are generated lazily; the resolve endpoint warms the cache.
        if (resp.statusCode() == 404 && fullUrl.contains("/media/cache/")
                && !fullUrl.contains("/media/cache/resolve/")) {
            Logger.debug("PartDB: 404 for {}, retrying via resolve URL", fullUrl);
            String resolveUrl = fullUrl.replace("/media/cache/", "/media/cache/resolve/");
            resp = doGetBytes(resolveUrl, !absolute);
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("HTTP " + resp.statusCode() + " " + fullUrl);
        }
        return resp.body();
    }

    private HttpResponse<byte[]> doGetBytes(String fullUrl, boolean withAuth) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Accept", "*/*")
                .GET()
                .timeout(Duration.ofSeconds(10));
        if (withAuth) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** Returns the best URL for an attachment object.
     *  Prefers internal_path (relative, served by this PartDB instance).
     *  Falls back to external_path only when allowExternalAttachments is enabled. */
    private String pickAttachmentUrl(JsonObject att) {
        if (att == null) {
            return null;
        }
        String intPath = att.has("internal_path") ? att.get("internal_path").getAsString() : "";
        if (!intPath.isEmpty()) {
            return intPath;
        }
        if (allowExternalAttachments) {
            String extPath = (att.has("external_path") && !att.get("external_path").isJsonNull())
                    ? att.get("external_path").getAsString() : "";
            if (!extPath.isEmpty()) {
                return extPath;
            }
        }
        return null;
    }

    private String extractKicadFootprint(JsonObject obj) {
        if (!obj.has("eda_info") || obj.get("eda_info").isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get("eda_info");
        JsonObject edaInfo = el.isJsonArray() && el.getAsJsonArray().size() > 0
                ? el.getAsJsonArray().get(0).getAsJsonObject()
                : el.isJsonObject() ? el.getAsJsonObject() : null;
        if (edaInfo != null && edaInfo.has("kicad_footprint") && !edaInfo.get("kicad_footprint").isJsonNull()) {
            String kfp = edaInfo.get("kicad_footprint").getAsString();
            return kfp.isEmpty() ? null : kfp;
        }
        return null;
    }

    private void applyPartData(Part part, JsonObject data, boolean isUpdate) throws Exception {
        // --- Collect all values via network calls on the current (background) thread ---

        String newName = null;
        if (data.has("description") && !data.get("description").isJsonNull()) {
            String desc = data.get("description").getAsString();
            if (!desc.isEmpty()) {
                newName = desc;
            }
        }

        // Always fetch the part detail — the search result omits parameters and may omit footprint.
        String newPkgName = null;
        String newKicadFootprint = null;
        Length newHeight = null;
        Double newBodyWidth = null;
        Double newBodyLength = null;
        boolean hasPartDbFootprint = false;
        if (data.has("id")) {
            try {
                String detailJson = request("GET", "/api/parts/" + data.get("id").getAsInt(), null);
                JsonObject detail = parseObject(detailJson);

                int footprintId = -1;
                if (detail.has("footprint") && !detail.get("footprint").isJsonNull()) {
                    JsonObject footprintObj = detail.getAsJsonObject("footprint");
                    if (footprintObj.has("name")) {
                        String fpName = footprintObj.get("name").getAsString();
                        if (!fpName.isEmpty()) {
                            newPkgName = fpName;
                        }
                    }
                    if (footprintObj.has("id")) {
                        footprintId = footprintObj.get("id").getAsInt();
                    }
                }

                newKicadFootprint = extractKicadFootprint(detail);

                JsonArray partParams = detail.has("parameters") ? detail.getAsJsonArray("parameters") : null;

                // Fetch footprint detail for package data (dimensions, KiCad footprint).
                JsonArray fpParams = null;
                if (footprintId >= 0) {
                    try {
                        String fpJson = request("GET", "/api/footprints/" + footprintId, null);
                        JsonObject fpDetail = parseObject(fpJson);
                        fpParams = fpDetail.has("parameters")
                                ? fpDetail.getAsJsonArray("parameters") : null;
                        if (newKicadFootprint == null) {
                            newKicadFootprint = extractKicadFootprint(fpDetail);
                        }
                        hasPartDbFootprint = true;
                    } catch (Exception e) {
                        Logger.debug("PartDB: could not fetch footprint detail: {}", e.getMessage());
                    }
                }

                // No PartDB footprint assigned but a KiCad reference exists — use the module
                // name (the part after ':') as the package ID so the pads can be imported.
                if (newPkgName == null && newKicadFootprint != null && newKicadFootprint.contains(":")) {
                    newPkgName = newKicadFootprint.split(":", 2)[1];
                }

                // Resolve body dims and height from part params first, footprint params as fallback.
                newBodyWidth  = resolveParamValue("width",  partParams, fpParams);
                newBodyLength = resolveParamValue("length", partParams, fpParams);
                Double h = resolveParamValue("height", partParams, fpParams);
                if (h != null) {
                    newHeight = new Length(h, LengthUnit.Millimeters);
                }

            } catch (Exception e) {
                Logger.debug("PartDB: could not fetch part detail: {}", e.getMessage());
            }
        }

        // Apply all model changes on the EDT to avoid Swing threading violations.
        final String fName = newName;
        final String fPkgName = newPkgName;
        final String fKicadFootprint = newKicadFootprint;
        final Length fHeight = newHeight;
        final Double fBodyWidth = newBodyWidth;
        final Double fBodyLength = newBodyLength;
        final boolean fHasPartDbFootprint = hasPartDbFootprint;
        final boolean skipFootprint = isUpdate ? disableFootprintOnUpdate : disableFootprintOnImport;

        Runnable applyChanges = () -> {
            if (fName != null) {
                part.setName(fName);
            }
            if (fPkgName != null && !skipFootprint) {
                Package existing = Configuration.get().getPackage(fPkgName);
                if (existing == null) {
                    existing = new Package(fPkgName);
                    Configuration.get().addPackage(existing);
                    Logger.info("PartDB: created package '{}'", fPkgName);
                }
                part.setPackage(existing);
                if (fBodyWidth != null) {
                    existing.getFootprint().setBodyWidth(fBodyWidth);
                }
                if (fBodyLength != null) {
                    existing.getFootprint().setBodyHeight(fBodyLength);
                }
                if (fKicadFootprint != null
                        && (!fHasPartDbFootprint || autoApplyKicadPads
                                || fBodyWidth != null || fBodyLength != null)) {
                    importKicadPads(existing, fKicadFootprint);
                }
            }
            if (fHeight != null) {
                part.setHeight(fHeight);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            applyChanges.run();
        } else {
            SwingUtilities.invokeAndWait(applyChanges);
        }
    }

    /** Returns the value_typical of the first parameter matching {@code name} (case-insensitive),
     *  fetching the full parameter detail if needed. Returns null if not found or value <= 0. */
    private Double fetchParamValue(JsonArray params, String name) {
        if (params == null) {
            return null;
        }
        for (JsonElement el : params) {
            JsonObject p = el.getAsJsonObject();
            if (name.equalsIgnoreCase(p.get("name").getAsString())) {
                JsonObject full = p;
                if (!p.has("value_typical") && p.has("id")) {
                    try {
                        full = parseObject(request("GET", "/api/parameters/" + p.get("id").getAsInt(), null));
                    } catch (Exception e) {
                        Logger.debug("PartDB: could not fetch parameter detail: {}", e.getMessage());
                    }
                }
                if (full.has("value_typical") && !full.get("value_typical").isJsonNull()) {
                    double v = full.get("value_typical").getAsDouble();
                    return v > 0 ? v : null;
                }
                return null;
            }
        }
        return null;
    }

    /** Resolves a parameter value by looking in partParams first, then fpParams as fallback. */
    private Double resolveParamValue(String name, JsonArray partParams, JsonArray fpParams) {
        Double v = fetchParamValue(partParams, name);
        return v != null ? v : fetchParamValue(fpParams, name);
    }

    private void pushHeightParameter(int partDbId, double heightMm) throws Exception {
        String partJson = request("GET", "/api/parts/" + partDbId, null);
        JsonObject partData = parseObject(partJson);

        int existingParamId = -1;
        if (partData.has("parameters")) {
            for (JsonElement el : partData.getAsJsonArray("parameters")) {
                JsonObject param = el.getAsJsonObject();
                if ("height".equalsIgnoreCase(param.get("name").getAsString())) {
                    existingParamId = param.get("id").getAsInt();
                    break;
                }
            }
        }

        String heightBody = "{\"_type\":\"Part\""
                + ",\"name\":\"height\""
                + ",\"symbol\":\"h\""
                + ",\"unit\":\"mm\""
                + ",\"value_typical\":" + heightMm
                + ",\"value_text\":\"\",\"group\":\"\"}";

        if (existingParamId >= 0) {
            // Update the existing parameter directly
            request("PATCH", "/api/parameters/" + existingParamId, heightBody);
            Logger.debug("PartDB: updated height parameter (id={}) for part id={}", existingParamId, partDbId);
        } else {
            // POST /api/parameters/ doesn't exist — add via PATCH on the part.
            // Include existing parameters with their @id (so they are updated, not recreated)
            // plus the new height entry without @id (so it is created).
            StringBuilder params = new StringBuilder("[");
            boolean first = true;
            if (partData.has("parameters")) {
                for (JsonElement el : partData.getAsJsonArray("parameters")) {
                    JsonObject param = el.getAsJsonObject();
                    if (!first) {
                        params.append(",");
                    }
                    first = false;
                    String iri = param.has("@id") ? param.get("@id").getAsString() : null;
                    params.append("{\"_type\":\"Part\"");
                    if (iri != null) {
                        params.append(",\"@id\":").append(jsonString(iri));
                    }
                    params.append(",\"name\":").append(jsonString(
                                    param.has("name") ? param.get("name").getAsString() : ""))
                          .append(",\"symbol\":").append(jsonString(
                                    param.has("symbol") ? param.get("symbol").getAsString() : ""))
                          .append(",\"unit\":").append(jsonString(
                                    param.has("unit") ? param.get("unit").getAsString() : ""))
                          .append(",\"value_typical\":")
                          .append(param.has("value_typical") ? param.get("value_typical").getAsDouble() : 0)
                          .append(",\"value_text\":\"\",\"group\":\"\"}");
                }
            }
            if (!first) {
                params.append(",");
            }
            params.append(heightBody).append("]");
            request("PATCH", "/api/parts/" + partDbId, "{\"parameters\":" + params + "}");
            Logger.debug("PartDB: created height parameter for part id={}", partDbId);
        }
    }

    private String buildPartJson(Part part) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\": ").append(jsonString(part.getId()));
        if (part.getName() != null && !part.getName().isEmpty()) {
            sb.append(", \"description\": ").append(jsonString(part.getName()));
        }
        // Include footprint IRI if a package with a matching PartDB footprint exists
        if (part.getPackage() != null) {
            try {
                String fpSearch = request("GET", "/api/footprints/?name=" + urlEncode(part.getPackage().getId()), null);
                JsonArray fpResults = parseArray(fpSearch);
                if (fpResults.size() > 0) {
                    int fpId = fpResults.get(0).getAsJsonObject().get("id").getAsInt();
                    sb.append(", \"footprint\": \"/api/footprints/").append(fpId).append("\"");
                }
            } catch (Exception e) {
                Logger.debug("PartDB: could not resolve footprint for package '{}': {}",
                        part.getPackage().getId(), e.getMessage());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private JsonObject parseObject(String json) {
        return JSON_PARSER.parse(json).getAsJsonObject();
    }

    @SuppressWarnings("deprecation")
    private JsonArray parseArray(String json) {
        return JSON_PARSER.parse(json).getAsJsonArray();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String urlEncode(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    }

    // -------------------------------------------------------------------------
    // HTTP primitives
    // -------------------------------------------------------------------------

    private String request(String method, String path, String body) throws Exception {
        String fullUrl = url.replaceAll("/+$", "") + path;
        Logger.trace("PartDB {} {}", method, fullUrl);

        boolean isPatch = "PATCH".equals(method);
        String contentType = isPatch ? "application/merge-patch+json" : "application/json";

        HttpRequest.BodyPublisher publisher = (body != null)
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + apiToken)
                .header("Accept", "application/json");

        if (body != null) {
            builder.header("Content-Type", contentType);
        }

        builder.method(method, publisher);

        long t0 = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long ms = System.currentTimeMillis() - t0;

        int status = response.statusCode();
        Logger.trace("PartDB {} {} -> {} ({}ms)", method, fullUrl, status, ms);

        if (status < 200 || status >= 300) {
            throw new IOException("PartDB HTTP " + status + " for " + method + " " + fullUrl
                    + " — " + response.body());
        }

        return response.body();
    }
}
