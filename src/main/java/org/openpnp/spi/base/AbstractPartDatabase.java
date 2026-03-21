package org.openpnp.spi.base;

import javax.swing.Action;
import javax.swing.Icon;

import org.openpnp.gui.support.PropertySheetWizardAdapter;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.spi.PartDatabase;
import org.openpnp.spi.PropertySheetHolder;
import org.simpleframework.xml.Attribute;

public abstract class AbstractPartDatabase extends AbstractModelObject implements PartDatabase {

    @Attribute(required = false)
    protected String url = "";

    @Attribute(required = false)
    protected String apiToken = "";

    protected transient boolean connected = false;

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getUrl() {
        return url;
    }

    @Override
    public void setUrl(String url) {
        Object old = this.url;
        this.url = url;
        firePropertyChange("url", old, url);
        connected = false;
    }

    @Override
    public String getApiToken() {
        return apiToken;
    }

    @Override
    public void setApiToken(String token) {
        Object old = this.apiToken;
        this.apiToken = token;
        firePropertyChange("apiToken", old, token);
        connected = false;
    }

    // PropertySheetHolder boilerplate

    @Override
    public String getPropertySheetHolderTitle() {
        return "Part Database";
    }

    @Override
    public PropertySheetHolder[] getChildPropertySheetHolders() {
        return null;
    }

    @Override
    public PropertySheet[] getPropertySheets() {
        return new PropertySheet[] {
            new PropertySheetWizardAdapter(getConfigurationWizard())
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
