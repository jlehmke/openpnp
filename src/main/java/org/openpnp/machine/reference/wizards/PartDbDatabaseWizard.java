package org.openpnp.machine.reference.wizards;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
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

    public PartDbDatabaseWizard(PartDbDatabase db) {
        this.db = db;
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null, "Connection", TitledBorder.LEADING,
                TitledBorder.TOP, null, null));
        contentPanel.add(panel);
        panel.setLayout(new FormLayout(
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
                }));

        panel.add(new JLabel("URL"), "2, 2, right, default");
        urlField = new JTextField();
        panel.add(urlField, "4, 2");

        panel.add(new JLabel("API Token"), "2, 4, right, default");
        tokenField = new JPasswordField();
        panel.add(tokenField, "4, 4");

        JButton testBtn = new JButton("Test Connection");
        testBtn.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            // Apply current field values before testing
            db.setUrl(urlField.getText().trim());
            db.setApiToken(new String(tokenField.getPassword()).trim());
            try {
                db.connect();
                JOptionPane.showMessageDialog(this,
                        "Connected successfully to " + db.getUrl(),
                        "PartDB Connection", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Connection failed: " + ex.getMessage(),
                        "PartDB Connection", JOptionPane.ERROR_MESSAGE);
            }
        }));
        panel.add(testBtn, "4, 6");
    }

    @Override
    public void createBindings() {
        addWrappedBinding(db, "url", urlField, "text");
        addWrappedBinding(db, "apiToken", tokenField, "text");
    }
}
