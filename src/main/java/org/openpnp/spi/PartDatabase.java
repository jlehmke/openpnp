package org.openpnp.spi;

import java.util.List;
import java.util.Map;

import org.openpnp.model.Part;

/**
 * SPI for external part databases. Implementations can connect to any part management
 * system (e.g. PartDB, InvenTree) to import/export part data and track stock.
 *
 * To add a new integration: implement this interface, extend AbstractPartDatabase,
 * and add the concrete class to machine.xml as:
 *   <part-database class="com.example.MyPartDatabase" .../>
 */
public interface PartDatabase extends WizardConfigurable, PropertySheetHolder {

    /** Establish connection to the external database. Throws on failure. */
    void connect() throws Exception;

    /** Returns true if the last connect() call succeeded. */
    boolean isConnected();

    String getUrl();
    void setUrl(String url);

    String getApiToken();
    void setApiToken(String token);

    /**
     * Import a part from the external database by its name and add it to the
     * OpenPnP configuration. The OpenPnP Part ID is set to the external part name.
     */
    Part importPart(String name) throws Exception;

    /**
     * Refresh the fields of an existing OpenPnP part from the external database
     * (looks up by Part ID / external name).
     */
    void updatePart(Part part) throws Exception;

    /**
     * Create or update the given OpenPnP part in the external database.
     */
    void pushPart(Part part) throws Exception;

    /**
     * Search for parts whose name contains the given query (case-insensitive).
     * Returns a list of matching part names. Default implementation throws
     * UnsupportedOperationException.
     */
    default List<String> searchParts(String query) throws Exception {
        throw new UnsupportedOperationException("searchParts not supported by this database");
    }

    /**
     * Record a single completed placement in the pending buffer (fast, no I/O).
     * Default is a no-op; implementations may override to track stock in real time.
     */
    default void trackPlacement(String partId) throws Exception {
    }

    /**
     * Like {@link #trackPlacement(String)} but targets a specific stock lot.
     * {@code lotId=-1} means use the part-level default lot. Default falls back to
     * {@link #trackPlacement(String)} so existing implementations are unaffected.
     */
    default void trackPlacement(String partId, int lotId) throws Exception {
        trackPlacement(partId);
    }

    /**
     * Called once when a job finishes. Implementations may auto-flush pending counts.
     * Default is a no-op.
     */
    default void onJobFinished() throws Exception {
    }

    /**
     * Called at job end with a map of {partId → completedPlacementCount}.
     * Accumulates counts into a pending buffer; call flushPlacements() to apply.
     * Default is a no-op; implementations may override to track stock.
     */
    default void recordPlacements(Map<String, Integer> partNameToCount) throws Exception {
    }

    /**
     * Send all accumulated pending placement counts to the external database.
     * Default is a no-op; implementations may override.
     */
    default void flushPlacements() throws Exception {
    }
}
