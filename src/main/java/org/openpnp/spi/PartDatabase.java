package org.openpnp.spi;

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
}
