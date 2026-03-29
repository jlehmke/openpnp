package org.openpnp.machine.reference.wizards;

import java.awt.Dimension;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.border.TitledBorder;

import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.machine.reference.KicadLibrary;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class KicadLibraryWizard extends AbstractConfigurationWizard {

    private final KicadLibrary kicadLibrary;
    private JTextArea kicadLibraryPathField;
    private JTextField kicadFootprintListUrlField;

    public KicadLibraryWizard(KicadLibrary kicadLibrary) {
        this.kicadLibrary = kicadLibrary;
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        JPanel panel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setBorder(new TitledBorder(null, "KiCad Libraries", TitledBorder.LEADING,
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
                        FormSpecs.DEFAULT_ROWSPEC,   // 2: library paths
                        FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC,   // 4: footprint list URL
                }));

        JLabel kicadLibLabel = new JLabel("<html>Library Paths<br><small>(local or HTTP)</small></html>");
        kicadLibLabel.setToolTipText("<html>One path or URL per line.<br>"
                + "Local: <tt>/usr/share/kicad/footprints</tt><br>"
                + "HTTP:&nbsp;&nbsp;<tt>https://raw.githubusercontent.com/KiCad/KiCad-Footprints/master</tt><br>"
                + "URL resolves to: <tt>{base}/{Library}.pretty/{Footprint}.kicad_mod</tt></html>");
        panel.add(kicadLibLabel, "2, 2, right, top");
        kicadLibraryPathField = new JTextArea(4, 0);
        kicadLibraryPathField.setLineWrap(false);
        panel.add(new JScrollPane(kicadLibraryPathField), "4, 2");

        JLabel kicadFpListLabel = new JLabel("Footprint List");
        kicadFpListLabel.setToolTipText("<html>Optional URL or file path to a footprints.txt (one Lib:FP per line).<br>"
                + "Used for HTTP library paths that cannot be enumerated directly.<br>"
                + "Example: <tt>http://host/kicad/footprints.txt</tt></html>");
        panel.add(kicadFpListLabel, "2, 4, right, default");
        kicadFootprintListUrlField = new JTextField();
        panel.add(kicadFootprintListUrlField, "4, 4");
    }

    @Override
    public void createBindings() {
        addWrappedBinding(kicadLibrary, "kicadLibraryPath", kicadLibraryPathField, "text");
        addWrappedBinding(kicadLibrary, "kicadFootprintListUrl", kicadFootprintListUrlField, "text");
    }
}
