package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.machine.reference.KicadLibrary;

/**
 * Modal dialog for selecting a KiCad footprint from the configured library sources.
 *
 * The filter field spans both columns. The left panel shows only libraries that have at least one
 * matching footprint; the right panel shows matching footprints for the selected library.
 *
 * After {@code setVisible(true)} returns, call:
 *   {@code getSelectedRef()}  — non-null if a Lib:FP reference was chosen
 *   {@code getSelectedFile()} — non-null if the user chose "Select from file"
 *   {@code isCancelled()}     — true if neither was chosen
 */
public class KicadHttpLibraryDialog extends JDialog {

    private final KicadLibrary kicadLibrary;

    /** Full library map loaded once: library name → all footprint names. */
    private final Map<String, List<String>> libraryMap = new TreeMap<>();

    /** Subset of libraryMap after applying the current filter. */
    private final Map<String, List<String>> filteredMap = new TreeMap<>();

    private final DefaultListModel<String> categoryModel = new DefaultListModel<>();
    private final JList<String>            categoryList  = new JList<>(categoryModel);

    private final DefaultListModel<String> partsModel = new DefaultListModel<>();
    private final JList<String>            partsList  = new JList<>(partsModel);

    private final JTextField filterField  = new JTextField(30);
    private final JLabel     statusLabel  = new JLabel(" ");
    private final JButton    okButton     = new JButton("OK");

    private String selectedRef  = null;
    private File   selectedFile = null;

    public KicadHttpLibraryDialog(Frame parent, KicadLibrary kicadLibrary) {
        super(parent, "Select KiCad Footprint", true);
        this.kicadLibrary = kicadLibrary;

        categoryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        categoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showPartsForSelectedLib();
            }
        });

        partsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        partsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                okButton.setEnabled(partsList.getSelectedValue() != null);
            }
        });
        partsList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && partsList.getSelectedValue() != null) {
                    doOk();
                }
            }
        });

        okButton.setEnabled(false);
        okButton.addActionListener(e -> doOk());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        JButton fileButton = new JButton("Select from file\u2026");
        fileButton.addActionListener(e -> doSelectFile());

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        // --- Layout ---
        JPanel filterPanel = new JPanel(new BorderLayout(4, 0));
        filterPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        filterPanel.add(new JLabel("Filter: "), BorderLayout.WEST);
        filterPanel.add(filterField, BorderLayout.CENTER);

        JScrollPane catScroll = new JScrollPane(categoryList);
        catScroll.setBorder(BorderFactory.createTitledBorder("Library"));

        JScrollPane partsScroll = new JScrollPane(partsList);
        partsScroll.setBorder(BorderFactory.createTitledBorder("Footprints"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, catScroll, partsScroll);
        splitPane.setDividerLocation(220);
        splitPane.setBorder(BorderFactory.createEmptyBorder(0, 8, 4, 8));

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(filterPanel, BorderLayout.NORTH);
        centerPanel.add(splitPane,   BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        JPanel bottomRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        bottomRight.add(cancelButton);
        bottomRight.add(okButton);

        JPanel bottomLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        bottomLeft.add(fileButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        bottomPanel.add(bottomLeft,  BorderLayout.WEST);
        bottomPanel.add(statusPanel, BorderLayout.CENTER);
        bottomPanel.add(bottomRight, BorderLayout.EAST);

        setLayout(new BorderLayout(0, 0));
        add(centerPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        setSize(720, 480);
        setLocationRelativeTo(parent);

        loadFootprints();
    }

    private void loadFootprints() {
        statusLabel.setText("Loading footprints\u2026");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return kicadLibrary.loadKicadFootprintList();
            }
            @Override
            protected void done() {
                try {
                    List<String> fps = get();
                    libraryMap.clear();
                    for (String ref : fps) {
                        int colon = ref.indexOf(':');
                        if (colon < 0) {
                            continue;
                        }
                        String lib = ref.substring(0, colon);
                        String fp  = ref.substring(colon + 1);
                        libraryMap.computeIfAbsent(lib, k -> new ArrayList<>()).add(fp);
                    }
                    applyFilter();
                    statusLabel.setText(fps.isEmpty()
                            ? "No footprints found."
                            : libraryMap.size() + " libraries, " + fps.size() + " footprints.");
                } catch (InterruptedException | ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    statusLabel.setText("Failed to load: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void applyFilter() {
        String filter = filterField.getText().trim().toLowerCase();
        String prevLib = categoryList.getSelectedValue();

        filteredMap.clear();
        for (Map.Entry<String, List<String>> entry : libraryMap.entrySet()) {
            String lib = entry.getKey();
            List<String> fps = entry.getValue();
            if (filter.isEmpty()) {
                filteredMap.put(lib, fps);
            } else if (lib.toLowerCase().contains(filter)) {
                // Whole library matches — show all its footprints
                filteredMap.put(lib, fps);
            } else {
                // Show only footprints whose name matches
                List<String> matching = new ArrayList<>();
                for (String fp : fps) {
                    if (fp.toLowerCase().contains(filter)) {
                        matching.add(fp);
                    }
                }
                if (!matching.isEmpty()) {
                    filteredMap.put(lib, matching);
                }
            }
        }

        categoryModel.clear();
        for (String lib : filteredMap.keySet()) {
            categoryModel.addElement(lib);
        }

        // Re-select the previously selected library if it is still visible
        if (prevLib != null && filteredMap.containsKey(prevLib)) {
            categoryList.setSelectedValue(prevLib, true);
        } else if (!categoryModel.isEmpty()) {
            categoryList.setSelectedIndex(0);
        } else {
            partsModel.clear();
            okButton.setEnabled(false);
        }
    }

    private void showPartsForSelectedLib() {
        String lib = categoryList.getSelectedValue();
        partsModel.clear();
        okButton.setEnabled(false);
        if (lib == null) {
            return;
        }
        List<String> fps = filteredMap.get(lib);
        if (fps != null) {
            for (String fp : fps) {
                partsModel.addElement(fp);
            }
        }
    }

    private void doOk() {
        String lib = categoryList.getSelectedValue();
        String fp  = partsList.getSelectedValue();
        if (lib == null || fp == null) {
            return;
        }
        selectedRef = lib + ":" + fp;
        dispose();
    }

    private void doSelectFile() {
        FileDialog fd = new FileDialog(this, "Select KiCad Footprint File", FileDialog.LOAD);
        fd.setFilenameFilter(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".kicad_mod");
            }
        });
        fd.setVisible(true);
        if (fd.getFile() != null) {
            selectedFile = new File(fd.getDirectory(), fd.getFile());
            dispose();
        }
    }

    /** The selected KiCad footprint reference (e.g. {@code "Lib:FP"}), or {@code null}. */
    public String getSelectedRef()  { return selectedRef; }

    /** The selected {@code .kicad_mod} file, or {@code null}. */
    public File   getSelectedFile() { return selectedFile; }

    /** {@code true} if the user cancelled without selecting anything. */
    public boolean isCancelled()    { return selectedRef == null && selectedFile == null; }
}
