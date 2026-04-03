package org.openpnp.machine.reference;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
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
import org.openpnp.gui.support.Wizard;
import org.openpnp.machine.reference.wizards.PartDbDatabaseWizard;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.ProjectFile;
import org.openpnp.model.ProjectRecord;
import org.openpnp.spi.ProjectStorage;
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
public class PartDbDatabase extends AbstractPartDatabase implements ProjectStorage {

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

    /** Pending placement counts not yet flushed to PartDB: partId → count. */
    private final transient Map<String, Integer> pendingPlacements = new ConcurrentHashMap<>();

    /** KiCad footprint refs selected by the user, to be written to eda_info on next push. */
    private final transient Map<String, String> pendingKicadFootprints = new ConcurrentHashMap<>();

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

    /**
     * Returns the configured {@link ProjectStorage} for the current machine, or {@code null} if
     * none is configured or connected. Convenience helper for GUI classes.
     */
    public static ProjectStorage getProjectStorage() {
        org.openpnp.spi.Machine machine = Configuration.get().getMachine();
        if (machine instanceof ReferenceMachine) {
            org.openpnp.spi.PartDatabase db = ((ReferenceMachine) machine).getPartDatabase();
            if (db instanceof ProjectStorage) {
                return (ProjectStorage) db;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    @Override
    public void connect() throws Exception {
        connected = false;
        partIdCache.clear();
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
        double widthMm = 0;
        double lengthMm = 0;
        if (part.getPackage() != null && part.getPackage().getFootprint() != null) {
            Footprint fp = part.getPackage().getFootprint();
            widthMm = fp.getBodyWidth();
            lengthMm = fp.getBodyHeight(); // OpenPnP bodyHeight = PartDB "length"
        }
        try {
            pushDimensionParameters(partDbId, heightMm, widthMm, lengthMm);
        } catch (Exception e) {
            Logger.warn("PartDB: could not push dimension parameters ({}): {}",
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

    private KicadLibrary getKicadLibrary() {
        try {
            return ((ReferenceMachine) Configuration.get().getMachine()).getKicadLibrary();
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Returns true if pads were successfully imported, false otherwise. */
    public boolean importKicadPads(Package pkg, String kicadFootprint) {
        KicadLibrary kl = getKicadLibrary();
        if (kl == null) {
            return false;
        }
        return kl.importKicadPads(pkg, kicadFootprint);
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

    /** Records a KiCad footprint reference to be pushed to PartDB eda_info on the next pushPart call. */
    public void setPendingKicadFootprint(String partId, String ref) {
        pendingKicadFootprints.put(partId, ref);
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

    /**
     * Pushes height, body-width, and body-length (in mm) as PartDB parameters.
     * Existing parameters are updated in-place; missing ones are created.
     * Zero/negative values are skipped.
     */
    private void pushDimensionParameters(int partDbId,
            double heightMm, double widthMm, double lengthMm) throws Exception {
        String[] names   = {"height", "width",  "length"};
        String[] symbols = {"h",      "w",      "l"};
        double[] values  = {heightMm, widthMm,  lengthMm};

        boolean anyToPush = false;
        for (double v : values) {
            if (v > 0) {
                anyToPush = true;
                break;
            }
        }
        if (!anyToPush) {
            return;
        }

        String partJson = request("GET", "/api/parts/" + partDbId, null);
        JsonObject partData = parseObject(partJson);

        // Map existing param names to their DB ids for in-place updates.
        int[] existingIds = {-1, -1, -1};
        if (partData.has("parameters")) {
            for (JsonElement el : partData.getAsJsonArray("parameters")) {
                JsonObject param = el.getAsJsonObject();
                String pName = param.get("name").getAsString();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equalsIgnoreCase(pName) && param.has("id")) {
                        existingIds[i] = param.get("id").getAsInt();
                        break;
                    }
                }
            }
        }

        // Update existing params via direct PATCH
        for (int i = 0; i < names.length; i++) {
            if (values[i] > 0 && existingIds[i] >= 0) {
                request("PATCH", "/api/parameters/" + existingIds[i],
                        buildDimParamJson(names[i], symbols[i], values[i]));
                Logger.debug("PartDB: updated {} parameter (id={}) for part id={}",
                        names[i], existingIds[i], partDbId);
            }
        }

        // Create missing params via POST /api/parameters (each individually)
        for (int i = 0; i < names.length; i++) {
            if (values[i] > 0 && existingIds[i] < 0) {
                String body = "{\"name\":" + jsonString(names[i])
                        + ",\"symbol\":" + jsonString(symbols[i])
                        + ",\"unit\":\"mm\",\"value_typical\":" + values[i]
                        + ",\"value_text\":\"\",\"group\":\"\""
                        + ",\"element\":\"/api/parts/" + partDbId + "\"}";
                request("POST", "/api/parameters", body);
                Logger.debug("PartDB: created {} parameter for part id={}", names[i], partDbId);
            }
        }
    }

    private String buildDimParamJson(String name, String symbol, double valueMm) {
        return "{\"_type\":\"Part\",\"name\":" + jsonString(name)
                + ",\"symbol\":" + jsonString(symbol)
                + ",\"unit\":\"mm\",\"value_typical\":" + valueMm
                + ",\"value_text\":\"\",\"group\":\"\"}";
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
        String pendingKicadRef = pendingKicadFootprints.remove(part.getId());
        if (pendingKicadRef != null) {
            sb.append(", \"eda_info\": {\"kicad_footprint\": ").append(jsonString(pendingKicadRef)).append("}");
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
    // ProjectStorage implementation
    // -------------------------------------------------------------------------

    /** Cache for attachment type name → PartDB attachment type ID. */
    private final transient Map<String, Integer> attachmentTypeIdCache = new ConcurrentHashMap<>();

    @Override
    public List<ProjectRecord> listProjects(String nameFilter) throws Exception {
        String encodedFilter = (nameFilter == null || nameFilter.isEmpty())
                ? "" : "&name=%25" + urlEncode(nameFilter) + "%25";
        List<ProjectRecord> result = new ArrayList<>();
        int page = 1;
        while (true) {
            String json = request("GET",
                    "/api/projects?itemsPerPage=30&page=" + page + encodedFilter, null);
            JsonArray arr = parseArray(json);
            if (arr.size() == 0) {
                break;
            }
            for (JsonElement el : arr) {
                JsonObject p = el.getAsJsonObject();
                String id = String.valueOf(p.get("id").getAsInt());
                String name = p.has("name") ? p.get("name").getAsString() : "";
                String desc = p.has("description") ? p.get("description").getAsString() : "";
                List<ProjectFile> files = parseAttachments(p);
                result.add(new ProjectRecord(id, name, desc, files));
            }
            if (arr.size() < 30) {
                break; // last page
            }
            page++;
        }
        return result;
    }

    @Override
    public List<ProjectFile> getProjectFiles(String projectId) throws Exception {
        String json = request("GET", "/api/projects/" + projectId, null);
        JsonObject project = parseObject(json);
        return parseAttachments(project);
    }

    private List<ProjectFile> parseAttachments(JsonObject projectObj) {
        List<ProjectFile> files = new ArrayList<>();
        if (!projectObj.has("attachments")) {
            return files;
        }
        JsonArray atts = projectObj.getAsJsonArray("attachments");
        for (JsonElement el : atts) {
            JsonObject att = el.getAsJsonObject();
            String id = String.valueOf(att.get("id").getAsInt());
            String name = att.has("name") ? att.get("name").getAsString() : "";
            String typeName = "";
            if (att.has("attachment_type") && !att.get("attachment_type").isJsonNull()) {
                JsonObject typeObj = att.getAsJsonObject("attachment_type");
                if (typeObj.has("name")) {
                    typeName = typeObj.get("name").getAsString();
                }
            }
            // Use the original attachment name as filename (for matching in putFile).
            // Build downloadUrl from internal_path or external_path.
            String filename = name;
            String downloadUrl = "";
            if (att.has("internal_path") && !att.get("internal_path").isJsonNull()) {
                String ip = att.get("internal_path").getAsString();
                if (!ip.isEmpty()) {
                    downloadUrl = url.replaceAll("/+$", "") + "/" + ip.replaceAll("^/+", "");
                }
            }
            if (downloadUrl.isEmpty() && att.has("external_path")
                    && !att.get("external_path").isJsonNull()) {
                String ep = att.get("external_path").getAsString();
                if (!ep.isEmpty() && allowExternalAttachments) {
                    downloadUrl = ep;
                }
            }
            files.add(new ProjectFile(id, filename, typeName, downloadUrl));
        }
        return files;
    }

    @Override
    public byte[] downloadFile(String projectId, String fileId) throws Exception {
        // Find the file in the project to get its download URL
        List<ProjectFile> files = getProjectFiles(projectId);
        for (ProjectFile f : files) {
            if (f.id.equals(fileId)) {
                return requestBytes(f.downloadUrl);
            }
        }
        throw new IOException("File ID " + fileId + " not found in project " + projectId);
    }

    @Override
    public void putFile(String projectId, String fileType, String filename, byte[] content)
            throws Exception {
        if (readOnly) {
            Logger.warn("PartDB is in read-only mode; skipping upload of {}", filename);
            return;
        }
        // Look for an existing attachment with the same filename in the project
        List<ProjectFile> existing = getProjectFiles(projectId);
        String existingId = null;
        for (ProjectFile f : existing) {
            if (filename.equals(f.filename)) {
                existingId = f.id;
                break;
            }
        }

        String b64 = Base64.getEncoder().encodeToString(content);
        String uploadBlock = "{\"data\":" + jsonString(b64)
                + ",\"filename\":" + jsonString(filename) + "}";

        if (existingId != null) {
            // Update existing attachment
            String body = "{\"upload\":" + uploadBlock + "}";
            request("PATCH", "/api/attachments/" + existingId, body);
            Logger.info("PartDB updated attachment '{}' (id={}) in project {}", filename,
                    existingId, projectId);
        } else {
            // Create new attachment
            int typeId = ensureAttachmentType(fileType);
            String body = "{"
                    + "\"_type\":\"Project\","
                    + "\"name\":" + jsonString(filename) + ","
                    + "\"attachment_type\":\"/api/attachment_types/" + typeId + "\","
                    + "\"element\":\"/api/projects/" + projectId + "\","
                    + "\"upload\":" + uploadBlock
                    + "}";
            request("POST", "/api/attachments", body);
            Logger.info("PartDB created attachment '{}' (type='{}') in project {}", filename,
                    fileType, projectId);
        }
    }

    @Override
    public Map<String, String> getDesignatorToPartName(String projectId) throws Exception {
        Map<String, String> result = new HashMap<>();
        int page = 1;
        while (true) {
            String json = request("GET",
                    "/api/projects/" + projectId + "/bom?itemsPerPage=30&page=" + page, null);
            JsonArray arr = parseArray(json);
            if (arr.size() == 0) {
                break;
            }
            for (JsonElement el : arr) {
                JsonObject entry = el.getAsJsonObject();
                if (!entry.has("part") || entry.get("part").isJsonNull()) {
                    continue;
                }
                JsonObject part = entry.getAsJsonObject("part");
                String partName = part.has("name") ? part.get("name").getAsString() : null;
                if (partName == null || partName.isEmpty()) {
                    continue;
                }
                String mountnames = entry.has("mountnames")
                        ? entry.get("mountnames").getAsString() : "";
                for (String mount : mountnames.split(",")) {
                    mount = mount.trim();
                    if (!mount.isEmpty()) {
                        result.put(mount, partName);
                    }
                }
            }
            if (arr.size() < 30) {
                break;
            }
            page++;
        }
        return result;
    }

    @Override
    public void syncBomEntries(String projectId,
            Map<String, List<String>> partToMountnames) throws Exception {
        if (readOnly) {
            Logger.warn("PartDB is in read-only mode; skipping BOM sync for project {}", projectId);
            return;
        }

        // Fetch current BOM: partName → {entryId}
        Map<String, Integer> partNameToEntryId = new HashMap<>();
        int page = 1;
        while (true) {
            String json = request("GET",
                    "/api/projects/" + projectId + "/bom?itemsPerPage=30&page=" + page, null);
            JsonArray arr = parseArray(json);
            if (arr.size() == 0) {
                break;
            }
            for (JsonElement el : arr) {
                JsonObject entry = el.getAsJsonObject();
                if (!entry.has("part") || entry.get("part").isJsonNull()) {
                    continue;
                }
                int entryId = entry.get("id").getAsInt();
                String partName = entry.getAsJsonObject("part").get("name").getAsString();
                partNameToEntryId.put(partName, entryId);
            }
            if (arr.size() < 30) {
                break;
            }
            page++;
        }

        for (Map.Entry<String, List<String>> e : partToMountnames.entrySet()) {
            String partName = e.getKey();
            List<String> mounts = e.getValue();
            Integer entryId = partNameToEntryId.get(partName);
            if (entryId == null) {
                continue; // not in PartDB BOM, skip
            }
            if (mounts.isEmpty()) {
                // No enabled placements left → delete BOM entry
                request("DELETE", "/api/project_bom_entries/" + entryId, null);
                Logger.info("PartDB: removed BOM entry for part '{}' (no enabled placements)",
                        partName);
            } else {
                String mountStr = String.join(",", mounts);
                double qty = mounts.size();
                String body = "{\"mountnames\":" + jsonString(mountStr)
                        + ",\"quantity\":" + qty + "}";
                request("PATCH", "/api/project_bom_entries/" + entryId, body);
                Logger.debug("PartDB: BOM entry for '{}' → mountnames='{}', qty={}",
                        partName, mountStr, (int) qty);
            }
        }
    }

    /**
     * Returns the PartDB attachment type ID for the given type name, creating
     * the type in PartDB if it does not exist yet. Results are cached.
     */
    private int ensureAttachmentType(String typeName) throws Exception {
        Integer cached = attachmentTypeIdCache.get(typeName);
        if (cached != null) {
            return cached;
        }
        // Search existing types
        int page = 1;
        while (true) {
            String json = request("GET", "/api/attachment_types?itemsPerPage=30&page=" + page, null);
            JsonArray arr = parseArray(json);
            for (JsonElement el : arr) {
                JsonObject t = el.getAsJsonObject();
                if (t.has("name") && typeName.equals(t.get("name").getAsString())) {
                    int id = t.get("id").getAsInt();
                    attachmentTypeIdCache.put(typeName, id);
                    return id;
                }
            }
            if (arr.size() < 30) {
                break;
            }
            page++;
        }
        // Not found — create it
        String body = "{\"name\":" + jsonString(typeName) + "}";
        String resp = request("POST", "/api/attachment_types", body);
        int id = parseObject(resp).get("id").getAsInt();
        attachmentTypeIdCache.put(typeName, id);
        Logger.info("PartDB created attachment type '{}' with id={}", typeName, id);
        return id;
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
