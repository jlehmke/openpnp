package org.openpnp.machine.reference;

import java.io.IOException;
import java.net.URI;
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
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.spi.base.AbstractPartDatabase;
import org.pmw.tinylog.Logger;
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

    @SuppressWarnings("deprecation")
    private static final JsonParser JSON_PARSER = new JsonParser();

    @Commit
    private void onLoad() {
        if (url != null && !url.isEmpty() && apiToken != null && !apiToken.isEmpty()) {
            Thread t = new Thread(() -> {
                try {
                    connect();
                } catch (Exception e) {
                    Logger.warn("PartDB auto-connect failed: {}", e.getMessage());
                }
            });
            t.setDaemon(true);
            t.setName("PartDB-connect");
            t.start();
        }
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
        request("GET", "/api/tokens/current", null);
        connected = true;
        Logger.info("PartDB connected to {}", url);
    }

    // -------------------------------------------------------------------------
    // Import / Update / Push
    // -------------------------------------------------------------------------

    @Override
    public Part importPart(String name) throws Exception {
        JsonObject partData = findPartByName(name);
        // Use the exact name from PartDB (preserves original casing) as the OpenPnP Part ID.
        String exactName = partData.get("name").getAsString();
        Part part = new Part(exactName);
        applyPartData(part, partData);
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
        applyPartData(part, partData);
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
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private JsonObject findPartByName(String name) throws Exception {
        String json = request("GET", "/api/parts/?name=" + urlEncode(name), null);
        JsonArray results = parseArray(json);
        if (results.size() == 0) {
            throw new Exception("PartDB: no part found with name '" + name + "'");
        }
        return results.get(0).getAsJsonObject();
    }

    private void applyPartData(Part part, JsonObject data) throws Exception {
        // --- Collect all values via network calls on the current (background) thread ---

        String newName = null;
        if (data.has("description") && !data.get("description").isJsonNull()) {
            String desc = data.get("description").getAsString();
            if (!desc.isEmpty()) {
                newName = desc;
            }
        }

        // Always fetch the part detail — the search result omits parameters and may omit footprint.
        // Extract package name and height from the detail response.
        String newPkgName = null;
        Length newHeight = null;
        if (data.has("id")) {
            try {
                String detailJson = request("GET", "/api/parts/" + data.get("id").getAsInt(), null);
                JsonObject detail = parseObject(detailJson);

                if (detail.has("footprint") && !detail.get("footprint").isJsonNull()) {
                    JsonObject footprint = detail.getAsJsonObject("footprint");
                    if (footprint.has("name")) {
                        String fpName = footprint.get("name").getAsString();
                        if (!fpName.isEmpty()) {
                            newPkgName = fpName;
                        }
                    }
                }

                if (detail.has("parameters")) {
                    for (JsonElement el : detail.getAsJsonArray("parameters")) {
                        JsonObject param = el.getAsJsonObject();
                        if ("height".equalsIgnoreCase(param.get("name").getAsString())
                                && param.has("value_typical") && !param.get("value_typical").isJsonNull()) {
                            double h = param.get("value_typical").getAsDouble();
                            if (h > 0) {
                                newHeight = new Length(h, LengthUnit.Millimeters);
                            }
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Logger.debug("PartDB: could not fetch part detail: {}", e.getMessage());
            }
        }

        // Apply all model changes on the EDT to avoid Swing threading violations.
        // Use invokeAndWait only when called from a background thread; run directly when already on EDT.
        final String fName = newName;
        final String fPkgName = newPkgName;
        final Length fHeight = newHeight;
        Runnable applyChanges = () -> {
            if (fName != null) {
                part.setName(fName);
            }
            if (fPkgName != null) {
                Package existing = Configuration.get().getPackage(fPkgName);
                if (existing == null) {
                    existing = new Package(fPkgName);
                    Configuration.get().addPackage(existing);
                    Logger.info("PartDB: created package '{}'", fPkgName);
                }
                part.setPackage(existing);
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

        HttpResponse<String> response = httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        int status = response.statusCode();
        Logger.trace("PartDB {} {} -> {}", method, fullUrl, status);

        if (status < 200 || status >= 300) {
            throw new IOException("PartDB HTTP " + status + " for " + method + " " + fullUrl
                    + " — " + response.body());
        }

        return response.body();
    }
}
