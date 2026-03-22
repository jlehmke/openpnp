package org.openpnp.machine.reference.wizards;

import java.awt.Color;
import java.awt.Dimension;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
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
    private JTextField urlField;
    private JPasswordField tokenField;
    private JLabel statusLabel;
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
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,
                }));

        dbPanel.add(new JLabel("URL"), "2, 2, right, default");
        urlField = new JTextField();
        dbPanel.add(urlField, "4, 2");

        dbPanel.add(new JLabel("API Token"), "2, 4, right, default");
        tokenField = new JPasswordField();
        dbPanel.add(tokenField, "4, 4");

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
        dbPanel.add(testBtn, "4, 6");

        statusLabel = new JLabel(" ");
        dbPanel.add(statusLabel, "4, 8");

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

        kicadPanel.add(new JLabel("Library Paths"), "2, 2, right, top");
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
        addWrappedBinding(db, "url", urlField, "text");
        addWrappedBinding(db, "apiToken", tokenField, "text");
        addWrappedBinding(db, "kicadLibraryPath", kicadLibraryPathField, "text");
    }
}
