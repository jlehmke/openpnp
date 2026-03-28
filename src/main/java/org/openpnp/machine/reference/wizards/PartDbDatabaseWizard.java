package org.openpnp.machine.reference.wizards;

import java.awt.Color;
import java.awt.Dimension;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.machine.reference.PartDbDatabase;
import org.openpnp.util.UiUtils;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class PartDbDatabaseWizard extends AbstractConfigurationWizard {

    private final PartDbDatabase db;
    private JCheckBox enabledCheckbox;
    private JTextField urlField;
    private JPasswordField tokenField;
    private JLabel statusLabel;
    private JCheckBox trackPlacementsCheckbox;
    private JCheckBox disableAutoFlushOnJobFinishCheckbox;
    private JCheckBox disableAutoFlushOnShutdownCheckbox;
    private JCheckBox readOnlyCheckbox;
    private JCheckBox hideStockLevelCheckbox;
    private JCheckBox autoApplyKicadPadsCheckbox;
    private JCheckBox disableFootprintOnImportCheckbox;
    private JCheckBox disableFootprintOnUpdateCheckbox;
    private JCheckBox allowExternalAttachmentsCheckbox;
    private JTextArea kicadLibraryPathField;

    public PartDbDatabaseWizard(PartDbDatabase db) {
        this.db = db;
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        // --- Part Database section ---
        JPanel dbPanel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        dbPanel.setBorder(new TitledBorder(null, "Part Database", TitledBorder.LEADING,
                TitledBorder.TOP, null, null));
        contentPanel.add(dbPanel);
        dbPanel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        ColumnSpec.decode("default:grow"),
                },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 2: enabled
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 4: URL
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 6: token
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 8: test button
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 10: status
                }));

        enabledCheckbox = new JCheckBox("Enable");
        dbPanel.add(enabledCheckbox, "4, 2");

        dbPanel.add(new JLabel("URL"), "2, 4, right, default");
        urlField = new JTextField();
        dbPanel.add(urlField, "4, 4");

        dbPanel.add(new JLabel("API Token"), "2, 6, right, default");
        tokenField = new JPasswordField();
        dbPanel.add(tokenField, "4, 6");

        JButton testBtn = new JButton("Test Connection");
        testBtn.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            db.setUrl(urlField.getText().trim());
            db.setApiToken(new String(tokenField.getPassword()).trim());
            try {
                db.connect();
            } catch (Exception ex) {
                statusLabel.setText("Failed: " + ex.getMessage());
                statusLabel.setForeground(Color.red);
            }
        }));
        dbPanel.add(testBtn, "4, 8");

        statusLabel = new JLabel(" ");
        dbPanel.add(statusLabel, "4, 10");

        // --- Stock Tracking section ---
        JPanel stockPanel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        stockPanel.setBorder(new TitledBorder(null, "Stock Tracking", TitledBorder.LEADING,
                TitledBorder.TOP, null, null));
        contentPanel.add(stockPanel);
        stockPanel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        ColumnSpec.decode("default:grow"),
                },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 2: track placements
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 4: auto-flush on job finish
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 6: auto-flush on shutdown
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 8: read-only
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 10: hide stock column
                }));

        trackPlacementsCheckbox = new JCheckBox("Track placements");
        stockPanel.add(trackPlacementsCheckbox, "4, 2");

        disableAutoFlushOnJobFinishCheckbox = new JCheckBox("Disable auto-flush on job finish");
        stockPanel.add(disableAutoFlushOnJobFinishCheckbox, "4, 4");

        disableAutoFlushOnShutdownCheckbox = new JCheckBox("Disable auto-flush on shutdown");
        stockPanel.add(disableAutoFlushOnShutdownCheckbox, "4, 6");

        readOnlyCheckbox = new JCheckBox("Read-only mode");
        stockPanel.add(readOnlyCheckbox, "4, 8");

        hideStockLevelCheckbox = new JCheckBox("Hide stock level column in parts list");
        stockPanel.add(hideStockLevelCheckbox, "4, 10");

        // --- Import section ---
        JPanel importPanel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        importPanel.setBorder(new TitledBorder(null, "Import", TitledBorder.LEADING,
                TitledBorder.TOP, null, null));
        contentPanel.add(importPanel);
        importPanel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        ColumnSpec.decode("default:grow"),
                },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 2: auto-apply kicad pads
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 4: disable footprint on import
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 6: disable footprint on update
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,  // 8: allow external attachments
                }));

        autoApplyKicadPadsCheckbox = new JCheckBox("Auto-apply KiCad pads on import/update");
        importPanel.add(autoApplyKicadPadsCheckbox, "4, 2");

        disableFootprintOnImportCheckbox = new JCheckBox("Disable auto-import of footprint");
        importPanel.add(disableFootprintOnImportCheckbox, "4, 4");

        disableFootprintOnUpdateCheckbox = new JCheckBox("Disable auto-update of footprint");
        importPanel.add(disableFootprintOnUpdateCheckbox, "4, 6");

        allowExternalAttachmentsCheckbox = new JCheckBox("Allow external attachment URLs (images, datasheets)");
        importPanel.add(allowExternalAttachmentsCheckbox, "4, 8");

        // --- KiCad Libraries section ---
        JPanel kicadPanel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        kicadPanel.setBorder(new TitledBorder(null, "KiCad Libraries", TitledBorder.LEADING,
                TitledBorder.TOP, null, null));
        contentPanel.add(kicadPanel);
        kicadPanel.setLayout(new FormLayout(
                new ColumnSpec[] {
                        FormSpecs.RELATED_GAP_COLSPEC,
                        FormSpecs.DEFAULT_COLSPEC,
                        FormSpecs.RELATED_GAP_COLSPEC,
                        ColumnSpec.decode("default:grow"),
                },
                new RowSpec[] {
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                }));

        JLabel kicadLibLabel = new JLabel("<html>Library Paths<br><small>(local or HTTP)</small></html>");
        kicadLibLabel.setToolTipText("<html>One path or URL per line.<br>"
                + "Local: <tt>/usr/share/kicad/footprints</tt><br>"
                + "HTTP:&nbsp;&nbsp;<tt>https://raw.githubusercontent.com/KiCad/KiCad-Footprints/master</tt><br>"
                + "URL resolves to: <tt>{base}/{Library}.pretty/{Footprint}.kicad_mod</tt></html>");
        kicadPanel.add(kicadLibLabel, "2, 2, right, top");
        kicadLibraryPathField = new JTextArea(4, 0);
        kicadLibraryPathField.setLineWrap(false);
        kicadPanel.add(new JScrollPane(kicadLibraryPathField), "4, 2");

        contentPanel.add(Box.createVerticalGlue());

        // Reflect current connection state immediately.
        updateStatusLabel(db.isConnected());

        // Keep the label in sync whenever connect() succeeds or fails.
        db.addPropertyChangeListener("connected", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                boolean nowConnected = Boolean.TRUE.equals(evt.getNewValue());
                SwingUtilities.invokeLater(() -> updateStatusLabel(nowConnected));
            }
        });
    }

    private void updateStatusLabel(boolean nowConnected) {
        if (nowConnected) {
            statusLabel.setText("Connected");
            statusLabel.setForeground(new Color(0, 128, 0));
        } else {
            statusLabel.setText("Not connected");
            statusLabel.setForeground(Color.red);
        }
    }

    @Override
    public void createBindings() {
        addWrappedBinding(db, "enabled", enabledCheckbox, "selected");
        addWrappedBinding(db, "url", urlField, "text");
        addWrappedBinding(db, "apiToken", tokenField, "text");
        addWrappedBinding(db, "trackPlacements", trackPlacementsCheckbox, "selected");
        addWrappedBinding(db, "disableAutoFlushOnJobFinish", disableAutoFlushOnJobFinishCheckbox, "selected");
        addWrappedBinding(db, "disableAutoFlushOnShutdown", disableAutoFlushOnShutdownCheckbox, "selected");
        addWrappedBinding(db, "readOnly", readOnlyCheckbox, "selected");
        addWrappedBinding(db, "hideStockLevel", hideStockLevelCheckbox, "selected");
        addWrappedBinding(db, "autoApplyKicadPads", autoApplyKicadPadsCheckbox, "selected");
        addWrappedBinding(db, "disableFootprintOnImport", disableFootprintOnImportCheckbox, "selected");
        addWrappedBinding(db, "disableFootprintOnUpdate", disableFootprintOnUpdateCheckbox, "selected");
        addWrappedBinding(db, "allowExternalAttachments", allowExternalAttachmentsCheckbox, "selected");
        addWrappedBinding(db, "kicadLibraryPath", kicadLibraryPathField, "text");
    }
}
