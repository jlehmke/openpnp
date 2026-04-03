package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.Translations;
import org.openpnp.model.ProjectFile;
import org.openpnp.model.ProjectRecord;
import org.openpnp.spi.ProjectStorage;

/**
 * Dialog for selecting a PartDB project and one of its file attachments.
 *
 * <p>Used in two modes:
 * <ul>
 *   <li><b>Board mode</b> ({@code panelMode=false}) — shows all projects; user picks a
 *       placement file to import as a board.</li>
 *   <li><b>Panel mode</b> ({@code panelMode=true}) — shows only projects that already have
 *       a {@code .board.xml} or {@code .panel.xml} attachment.</li>
 * </ul>
 */
public class ProjectBoardImportDialog extends JDialog {

    private final ProjectStorage db;
    private final boolean panelMode;

    private final JTextField searchField = new JTextField(28);
    private final DefaultListModel<ProjectRecord> projectModel = new DefaultListModel<>();
    private final JList<ProjectRecord> projectList = new JList<>(projectModel);
    private final DefaultListModel<ProjectFile> fileModel = new DefaultListModel<>();
    private final JList<ProjectFile> fileList = new JList<>(fileModel);
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton okButton = new JButton();
    private final JCheckBox chkImportMissingParts = new JCheckBox(
            Translations.getString("KicadPosImporterDialog.OptionsPanel.createMissingPartsChkbox.text"), true);

    private Timer debounceTimer;
    private SwingWorker<List<ProjectRecord>, Void> currentWorker;

    private ProjectRecord selectedProject = null;
    private ProjectFile selectedFile = null;
    private boolean createMode = false;

    public ProjectBoardImportDialog(Frame parent, ProjectStorage db, boolean panelMode) {
        super(parent,
                panelMode ? Translations.getString("ProjectBoardImportDialog.title.panel") : Translations.getString("ProjectBoardImportDialog.title.board"),
                true);
        this.db = db;
        this.panelMode = panelMode;

        // Project list
        projectList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        projectList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onProjectSelected(projectList.getSelectedValue());
            }
        });
        projectList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && fileList.getSelectedValue() != null) {
                    doOk();
                }
            }
        });

        // File list
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setVisibleRowCount(6);
        fileList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof ProjectFile) {
                    ProjectFile f = (ProjectFile) value;
                    setText(f.filename + "  \u2014  " + f.getTypeLabel());
                }
                return this;
            }
        });
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                if (!createMode) {
                    okButton.setEnabled(fileList.getSelectedValue() != null);
                }
            }
        });
        fileList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && fileList.getSelectedValue() != null) {
                    doOk();
                }
            }
        });

        okButton.setText(panelMode ? Translations.getString("ProjectBoardImportDialog.okButton.panel") : Translations.getString("ProjectBoardImportDialog.okButton.board"));
        okButton.setEnabled(false);
        okButton.addActionListener(e -> doOk());

        JButton cancelButton = new JButton(Translations.getString("ProjectBoardImportDialog.cancelButton.text"));
        cancelButton.addActionListener(e -> dispose());

        // ESC closes
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        // Debounce search
        debounceTimer = new Timer(300, e -> triggerSearch());
        debounceTimer.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounceTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounceTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounceTimer.restart(); }
        });

        // Layout
        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        searchPanel.add(new JLabel(Translations.getString("ProjectBoardImportDialog.searchLabel.text")), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(searchPanel, BorderLayout.NORTH);
        northPanel.add(statusPanel, BorderLayout.SOUTH);

        JScrollPane projectScroll = new JScrollPane(projectList);
        projectScroll.setBorder(
                BorderFactory.createTitledBorder(Translations.getString("ProjectBoardImportDialog.projectsPanel.border")));
        projectScroll.setPreferredSize(new Dimension(420, 160));

        JScrollPane fileScroll = new JScrollPane(fileList);
        fileScroll.setBorder(BorderFactory.createTitledBorder(Translations.getString("ProjectBoardImportDialog.filesPanel.border")));
        fileScroll.setPreferredSize(new Dimension(420, 130));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                projectScroll, fileScroll);
        splitPane.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        splitPane.setResizeWeight(0.55);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnRow.add(cancelButton);
        btnRow.add(okButton);

        JPanel southPanel = new JPanel(new BorderLayout());
        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        optionsPanel.add(chkImportMissingParts);
        southPanel.add(optionsPanel, BorderLayout.NORTH);
        southPanel.add(btnRow, BorderLayout.SOUTH);

        setLayout(new BorderLayout(0, 4));
        add(northPanel, BorderLayout.NORTH);
        add(splitPane, BorderLayout.CENTER);
        add(southPanel, BorderLayout.SOUTH);

        setSize(450, 420);
        setLocationRelativeTo(parent);
        searchField.requestFocusInWindow();

        // Load all projects immediately on open
        triggerSearch();
    }

    private void triggerSearch() {
        String query = searchField.getText().trim();

        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
        statusLabel.setText(Translations.getString("ProjectBoardImportDialog.status.loading"));
        projectModel.clear();
        fileModel.clear();
        okButton.setEnabled(false);

        currentWorker = new SwingWorker<List<ProjectRecord>, Void>() {
            @Override
            protected List<ProjectRecord> doInBackground() throws Exception {
                List<ProjectRecord> all = db.listProjects(query);
                if (!panelMode) {
                    return all;
                }
                // Panel mode: only projects with a .board.xml or .panel.xml file
                List<ProjectRecord> filtered = new ArrayList<>();
                for (ProjectRecord r : all) {
                    for (ProjectFile f : r.files) {
                        String lower = f.filename.toLowerCase();
                        if (lower.endsWith(".board.xml") || lower.endsWith(".panel.xml")) {
                            filtered.add(r);
                            break;
                        }
                    }
                }
                return filtered;
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    List<ProjectRecord> results = get();
                    projectModel.clear();
                    for (ProjectRecord r : results) {
                        projectModel.addElement(r);
                    }
                    statusLabel.setText(results.isEmpty()
                            ? Translations.getString("ProjectBoardImportDialog.status.noProjects")
                            : String.format(Translations.getString("ProjectBoardImportDialog.status.projectCount"), results.size()));
                } catch (Exception e) {
                    statusLabel.setText(String.format(Translations.getString("ProjectBoardImportDialog.status.error"), e.getMessage()));
                }
            }
        };
        currentWorker.execute();
    }

    private static boolean isImportable(ProjectFile f) {
        String name = f.filename.toLowerCase();
        return name.endsWith(".board.xml") || name.endsWith(".pos") || name.endsWith(".csv");
    }

    private void onProjectSelected(ProjectRecord project) {
        fileModel.clear();
        okButton.setEnabled(false);
        createMode = false;
        okButton.setText(panelMode ? Translations.getString("ProjectBoardImportDialog.okButton.panel") : Translations.getString("ProjectBoardImportDialog.okButton.board"));
        if (project == null) {
            return;
        }
        List<ProjectFile> files = project.files;
        if (panelMode) {
            for (ProjectFile f : files) {
                String lower = f.filename.toLowerCase();
                if (lower.endsWith(".board.xml") || lower.endsWith(".panel.xml")) {
                    fileModel.addElement(f);
                }
            }
        } else {
            for (ProjectFile f : files) {
                fileModel.addElement(f);
            }
            // If no importable files exist, switch to create mode
            boolean hasImportable = false;
            for (ProjectFile f : files) {
                if (isImportable(f)) {
                    hasImportable = true;
                    break;
                }
            }
            if (!hasImportable) {
                createMode = true;
                okButton.setText(Translations.getString("ProjectBoardImportDialog.okButton.createBoard"));
                okButton.setEnabled(true);
                return;
            }
        }
        // Auto-select if only one file
        if (fileModel.size() == 1) {
            fileList.setSelectedIndex(0);
        }
    }

    private void doOk() {
        selectedProject = projectList.getSelectedValue();
        selectedFile = fileList.getSelectedValue();
        if (selectedProject != null && (selectedFile != null || createMode)) {
            dispose();
        }
    }

    /** Returns {@code true} if missing parts should be imported/created from PartDB after import. */
    public boolean isImportMissingParts() {
        return chkImportMissingParts.isSelected();
    }

    /** Returns {@code true} if the user chose to create a new empty board (no importable files). */
    public boolean isCreateMode() {
        return createMode;
    }

    /** Returns the selected project, or {@code null} if the dialog was cancelled. */
    public ProjectRecord getSelectedProject() {
        return selectedProject;
    }

    /** Returns the selected file, or {@code null} if the dialog was cancelled. */
    public ProjectFile getSelectedFile() {
        return selectedFile;
    }
}
