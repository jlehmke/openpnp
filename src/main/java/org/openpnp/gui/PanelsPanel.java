/*
 * Copyright (C) 2022 Jason von Nieda <jason@vonnieda.org>, Tony Luken <tonyluken62+openpnp@gmail.com>
 * 
 * This file is part of OpenPnP.
 * 
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 * 
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.TitledBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableRowSorter;

import javax.swing.JOptionPane;

import org.openpnp.ConfigurationListener;
import org.openpnp.Translations;
import org.openpnp.events.PlacementsHolderLocationSelectedEvent;
import org.openpnp.events.PlacementsHolderSelectedEvent;
import org.openpnp.events.PlacementSelectedEvent;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.gui.support.ActionGroup;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.LengthCellValue;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.MonospacedFontTableCellRenderer;
import org.openpnp.gui.support.MultisortTableHeaderCellRenderer;
import org.openpnp.gui.support.TableUtils;
import org.openpnp.gui.tablemodel.PlacementsHolderTableModel;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Configuration.TablesLinked;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Panel;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.Placement;
import org.openpnp.machine.reference.PartDbDatabase;
import org.openpnp.model.ProjectFile;
import org.openpnp.model.ProjectRecord;
import org.openpnp.spi.Machine;
import org.openpnp.spi.ProjectStorage;
import com.google.common.eventbus.Subscribe;

@SuppressWarnings("serial")
public class PanelsPanel extends JPanel {
    final private Configuration configuration;
    final private MainFrame frame;

    private static final String PREF_DIVIDER_POSITION = "PanelsPanel.dividerPosition"; //$NON-NLS-1$
    private static final int PREF_DIVIDER_POSITION_DEF = -1;

    private PlacementsHolderTableModel panelsTableModel;
    private JTable panelsTable;
    private JSplitPane splitPane;

    private ActionGroup singleSelectionActionGroup;
    private ActionGroup multiSelectionActionGroup;
    private ActionGroup partDbSelectionActionGroup;
    private JProgressBar importProgressBar;

    private Component partDbSeparator;
    private JButton btnImportFromPartDb;
    private JButton btnPullFromPartDb;
    private JButton btnPushToPartDb;

    private Preferences prefs = Preferences.userNodeForPackage(PanelsPanel.class);

    private final PanelDefinitionPanel panelDefinitionPanel;

    public PanelsPanel(Configuration configuration, MainFrame frame) {
        this.configuration = configuration;
        this.frame = frame;
        
        singleSelectionActionGroup = new ActionGroup(removePanelAction, copyPanelAction);
        singleSelectionActionGroup.setEnabled(false);

        multiSelectionActionGroup = new ActionGroup(removePanelAction);
        multiSelectionActionGroup.setEnabled(false);

        partDbSelectionActionGroup = new ActionGroup(pushPanelToPartDbAction, pullPanelFromPartDbAction);
        partDbSelectionActionGroup.setEnabled(false);
        
        panelsTableModel = new PlacementsHolderTableModel(configuration, 
                () -> configuration.getPanels(), Panel.class);
        configuration.addPropertyChangeListener("panels", new PropertyChangeListener() { //$NON-NLS-1$

            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                panelsTableModel.fireTableDataChanged();
            }});
        
        panelsTable = new AutoSelectTextTable(panelsTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                java.awt.Point p = e.getPoint();
                int row = rowAtPoint(p);
                int col = columnAtPoint(p);
                if (row >= 0) {
                    if (col == 0) {
                        row = panelsTable.convertRowIndexToModel(row);
                        return configuration.getPanels().get(row).getFile().toString();
                    }
                }
                return super.getToolTipText();
            }

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    int modelRow = convertRowIndexToModel(row);
                    List<Panel> panels = configuration.getPanels();
                    if (modelRow < panels.size()) {
                        Panel p = panels.get(modelRow);
                        if (p.getPartDbProjectId() != null && p.isDirty()) {
                            c.setBackground(new Color(255, 230, 80));
                        } else {
                            Color bg = UIManager.getColor("Table.alternateRowColor");
                            c.setBackground((row % 2 == 0 || bg == null) ? getBackground() : bg);
                        }
                    }
                }
                return c;
            }
        };

        TableRowSorter<PlacementsHolderTableModel> panelsTableSorter = new TableRowSorter<>(panelsTableModel);
        panelsTable.setRowSorter(panelsTableSorter);
        panelsTable.getTableHeader().setDefaultRenderer(new MultisortTableHeaderCellRenderer());
        panelsTable.setDefaultRenderer(LengthCellValue.class, new MonospacedFontTableCellRenderer());
        panelsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        panelsTable.setAutoResizeMode(JTable.AUTO_RESIZE_NEXT_COLUMN);
        
        TableUtils.setColumnAlignment(panelsTableModel, panelsTable);
        
        TableUtils.installColumnWidthSavers(panelsTable, prefs, "PanelsPanel.panelsTable.columnWidth"); //$NON-NLS-1$
        
        
        panelsTable.getModel().addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                SwingUtilities.invokeLater(() -> {
                    panelDefinitionPanel.refresh();
                });
            }
        });

        panelsTable.getSelectionModel()
                .addListSelectionListener(new ListSelectionListener() {
                    @Override
                    public void valueChanged(ListSelectionEvent e) {
                        if (e.getValueIsAdjusting()) {
                            return;
                        }
                        
                        boolean updateLinkedTables = MainFrame.get().getTabs().getSelectedComponent() == MainFrame.get().getPanelsTab() 
                                && Configuration.get().getTablesLinked() == TablesLinked.Linked;

                        List<Panel> selections = getSelections();
                        partDbSelectionActionGroup.setEnabled(
                                selections.size() == 1
                                && selections.get(0).getPartDbProjectId() != null
                                && PartDbDatabase.getProjectStorage() != null);
                        if (selections.size() == 0) {
                            singleSelectionActionGroup.setEnabled(false);
                            multiSelectionActionGroup.setEnabled(false);
                            try {
                                panelDefinitionPanel.setPanel(null);
                            }
                            catch (IOException e1) {
                                // TODO Auto-generated catch block
                                e1.printStackTrace();
                            }
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderSelectedEvent(null, PanelsPanel.this));
                            }
                        }
                        else if (selections.size() == 1) {
                            multiSelectionActionGroup.setEnabled(false);
                            singleSelectionActionGroup.setEnabled(true);
                            try {
                                panelDefinitionPanel.setPanel((Panel) selections.get(0));
                            }
                            catch (IOException e1) {
                                // TODO Auto-generated catch block
                                e1.printStackTrace();
                            }
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderSelectedEvent(selections.get(0), PanelsPanel.this));
                            }
                        }
                        else {
                            singleSelectionActionGroup.setEnabled(false);
                            multiSelectionActionGroup.setEnabled(true);
                            try {
                                panelDefinitionPanel.setPanel(null);
                            }
                            catch (IOException e1) {
                                // TODO Auto-generated catch block
                                e1.printStackTrace();
                            }
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderSelectedEvent(null, PanelsPanel.this));
                            }
                        }
                    }
                });

        setLayout(new BorderLayout(0, 0));

        splitPane = new JSplitPane();
        splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        splitPane.setBorder(null);
        splitPane.setContinuousLayout(true);
        splitPane.setDividerLocation(prefs.getInt(PREF_DIVIDER_POSITION, PREF_DIVIDER_POSITION_DEF));
        splitPane.addPropertyChangeListener("dividerLocation", new PropertyChangeListener() { //$NON-NLS-1$
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                prefs.putInt(PREF_DIVIDER_POSITION, splitPane.getDividerLocation());
            }
        });

        JPanel pnlPanels = new JPanel();
        pnlPanels.setBorder(new TitledBorder(null,
                Translations.getString("PanelsPanel.Tab.Panels"), //$NON-NLS-1$
                TitledBorder.LEADING, TitledBorder.TOP, null)); //$NON-NLS-1$
        pnlPanels.setLayout(new BorderLayout(0, 0));

        JPanel toolbarRow = new JPanel(new BorderLayout());

        JToolBar toolBarPanels = new JToolBar();
        toolBarPanels.setFloatable(false);
        toolbarRow.add(toolBarPanels, BorderLayout.CENTER);

        importProgressBar = new JProgressBar();
        importProgressBar.setStringPainted(true);
        importProgressBar.setPreferredSize(new Dimension(150, importProgressBar.getPreferredSize().height));
        importProgressBar.setVisible(false);
        JPanel progressPanel = new JPanel();
        progressPanel.add(importProgressBar);
        toolbarRow.add(progressPanel, BorderLayout.EAST);

        pnlPanels.add(toolbarRow, BorderLayout.NORTH);

        JButton btnAddPanel = new JButton(addPanelAction);
        btnAddPanel.setHideActionText(true);
        btnAddPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                JPopupMenu menu = new JPopupMenu();
                menu.add(new JMenuItem(addNewPanelAction));
                menu.add(new JMenuItem(addExistingPanelAction));
                menu.show(btnAddPanel, (int) btnAddPanel.getWidth(), (int) btnAddPanel.getHeight());
            }
        });
        toolBarPanels.add(btnAddPanel);

        JButton btnRemovePanel = new JButton(removePanelAction);
        btnRemovePanel.setHideActionText(true);
        toolBarPanels.add(btnRemovePanel);

        JButton btnCopyPanel = new JButton(copyPanelAction);
        btnCopyPanel.setHideActionText(true);
        toolBarPanels.add(btnCopyPanel);

        partDbSeparator = new JToolBar.Separator();
        toolBarPanels.add(partDbSeparator);

        btnImportFromPartDb = new JButton(importPanelFromProjectAction);
        btnImportFromPartDb.setHideActionText(true);
        toolBarPanels.add(btnImportFromPartDb);

        btnPullFromPartDb = new JButton(pullPanelFromPartDbAction);
        btnPullFromPartDb.setHideActionText(true);
        toolBarPanels.add(btnPullFromPartDb);

        btnPushToPartDb = new JButton(pushPanelToPartDbAction);
        btnPushToPartDb.setHideActionText(true);
        toolBarPanels.add(btnPushToPartDb);

        toolBarPanels.addSeparator();

        JButton btnCleanUp = new JButton(cleanUpAction);
        btnCleanUp.setHideActionText(true);
        toolBarPanels.add(btnCleanUp);
        
        pnlPanels.add(new JScrollPane(panelsTable));
        splitPane.setLeftComponent(pnlPanels);
        
        panelDefinitionPanel = new PanelDefinitionPanel(this);
        splitPane.setRightComponent(panelDefinitionPanel);
        
        add(splitPane);
        

        configuration.addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration cfg) {
                SwingUtilities.invokeLater(() -> {
                    updatePartDbVisibility();
                    PartDbDatabase db = PartDbDatabase.getInstance();
                    if (db != null) {
                        db.addPropertyChangeListener("enabled", evt ->
                                SwingUtilities.invokeLater(() -> updatePartDbVisibility()));
                    }
                });
            }
        });

        Configuration.get().getBus().register(this);
    }

    private void updatePartDbVisibility() {
        boolean enabled = PartDbDatabase.getProjectStorage() != null;
        partDbSeparator.setVisible(enabled);
        btnImportFromPartDb.setVisible(enabled);
        btnPullFromPartDb.setVisible(enabled);
        btnPushToPartDb.setVisible(enabled);
    }

    public JTable getPanelsTable() {
        return panelsTable;
    }

    @Subscribe
    public void panelLocationSelected(PlacementsHolderLocationSelectedEvent event) {
        if (event.source == this || event.source == panelDefinitionPanel || event.placementsHolderLocation == null) {
            return;
        }
        if (event.placementsHolderLocation.getPlacementsHolder() instanceof Panel) {
            SwingUtilities.invokeLater(() -> {
                selectPanel((Panel) event.placementsHolderLocation.getPlacementsHolder().getDefinition());
            });
        }
        else if (event.placementsHolderLocation.getParent() instanceof PanelLocation) {
            SwingUtilities.invokeLater(() -> {
                selectPanel((Panel) event.placementsHolderLocation.getParent().getPlacementsHolder().getDefinition());
                panelDefinitionPanel.selectChild(event.placementsHolderLocation);
            });
        }
    }

    @Subscribe
    public void placementSelected(PlacementSelectedEvent event) {
        if (event.source == this || event.source == panelDefinitionPanel || 
                event.placementsHolderLocation == null || !(event.placementsHolderLocation.getPlacementsHolder() instanceof Panel)) {
            return;
        }
        Placement placement = event.placement == null ? null : (Placement) event.placement.getDefinition();
        SwingUtilities.invokeLater(() -> {
            selectPanel((Panel) event.placementsHolderLocation.getPlacementsHolder().getDefinition());
            panelDefinitionPanel.selectFiducial(placement);
        });
    }

    private void selectPanel(Panel panel) {
        if (panel == null) {
            panelsTable.getSelectionModel().clearSelection();
            return;
        }
        for (int i = 0; i < panelsTableModel.getRowCount(); i++) {
            if (configuration.getPanels().get(i) == panel) {
                int index = panelsTable.convertRowIndexToView(i);
                panelsTable.getSelectionModel().setSelectionInterval(index, index);
                panelsTable.scrollRectToVisible(
                        new Rectangle(panelsTable.getCellRect(index, 0, true)));
                break;
            }
        }
    }

    public void refresh() {
        panelsTableModel.fireTableDataChanged();
    }

    public void refreshSelectedRow() {
        int index = panelsTable.convertRowIndexToModel(panelsTable.getSelectedRow());
        panelsTableModel.fireTableRowsUpdated(index, index);
    }

    public Panel getSelection() {
        List<Panel> selections = getSelections();
        if (selections.isEmpty()) {
            return null;
        }
        return selections.get(0);
    }

    public List<Panel> getSelections() {
        ArrayList<Panel> selections = new ArrayList<>();
        int[] selectedRows = panelsTable.getSelectedRows();
        for (int selectedRow : selectedRows) {
            selectedRow = panelsTable.convertRowIndexToModel(selectedRow);
            selections.add(configuration.getPanels().get(selectedRow));
        }
        return selections;
    }

    public final Action addPanelAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("PanelsPanel.Action.AddPanel")); //$NON-NLS-1$
            putValue(SMALL_ICON, Icons.add);
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.AddPanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_A);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {}
    };

    public final Action addNewPanelAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("PanelsPanel.Action.AddPanel.NewPanel")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.AddPanel.NewPanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            FileDialog fileDialog = new FileDialog(frame, 
                    Translations.getString("PanelsPanel.Action.AddPanel.NewPanel.SaveDialog"), //$NON-NLS-1$
                    FileDialog.SAVE);
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".panel.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.panel.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                String filename = fileDialog.getFile();
                if (filename == null) {
                    return;
                }
                if (!filename.toLowerCase().endsWith(".panel.xml")) { //$NON-NLS-1$
                    filename = filename + ".panel.xml"; //$NON-NLS-1$
                }
                File file = new File(new File(fileDialog.getDirectory()), filename);

                Panel panel = addPanel(file);

                selectPanel(panel);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(frame, 
                        Translations.getString("PanelsPanel.Action.AddPanel.NewPanel.ErrorMessage"), //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    public final Action addExistingPanelAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("PanelsPanel.Action.AddPanel.ExistingPanel")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.AddPanel.ExistingPanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_E);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            FileDialog fileDialog = new FileDialog(frame);
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".panel.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.panel.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                if (fileDialog.getFile() == null) {
                    return;
                }
                File file = new File(new File(fileDialog.getDirectory()), fileDialog.getFile());

                Panel panel = addPanel(file);

                selectPanel(panel);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(frame, 
                        Translations.getString("PanelsPanel.Action.AddPanel.ExistingPanel.ErrorMessage"), //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    public final Action importPanelFromProjectAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbAdd);
            putValue(NAME, Translations.getString("PanelsPanel.Action.ImportPanelFromPartDB.name"));
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("PanelsPanel.Action.ImportPanelFromPartDB.description"));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            ProjectStorage db = PartDbDatabase.getProjectStorage();
            if (db == null || !db.isConnected()) {
                JOptionPane.showMessageDialog(frame,
                        Translations.getString("PartDb.Dialog.noProjectStorage.message"),
                        Translations.getString("PartDb.Dialog.noProjectStorage.title"),
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            ProjectBoardImportDialog dlg = new ProjectBoardImportDialog(frame, db, true);
            dlg.setVisible(true);

            ProjectRecord project = dlg.getSelectedProject();
            ProjectFile boardFileRecord = dlg.getSelectedFile();
            if (project == null || boardFileRecord == null) {
                return;
            }

            // Ask user where to save the panel file
            FileDialog fileDialog = new FileDialog(frame, Translations.getString("PanelsPanel.Action.ImportPanelFromPartDB.saveDialogTitle"), FileDialog.SAVE);
            fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".panel.xml"));
            fileDialog.setFile(project.name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".panel.xml");
            fileDialog.setVisible(true);
            if (fileDialog.getFile() == null) {
                return;
            }
            String filename = fileDialog.getFile();
            if (!filename.toLowerCase().endsWith(".panel.xml")) {
                filename = filename + ".panel.xml";
            }
            final File panelFile = new File(fileDialog.getDirectory(), filename);

            // Derive panel name
            String fileStem = panelFile.getName();
            if (fileStem.toLowerCase().endsWith(".panel.xml")) {
                fileStem = fileStem.substring(0, fileStem.length() - ".panel.xml".length());
            }
            final String projectStem = project.name.replaceAll("[^a-zA-Z0-9._-]", "_");
            final String panelName = (!fileStem.equals(projectStem)
                    && !fileStem.equals(project.name))
                    ? project.name + " [" + fileStem + "]"
                    : project.name;

            final boolean importMissingParts = dlg.isImportMissingParts();
            final boolean isPanelXml =
                    boardFileRecord.filename.toLowerCase().endsWith(".panel.xml");
            final File panelDir = panelFile.getParentFile();

            // Ask about board file conflicts on EDT before starting the worker
            final boolean downloadBoardFiles;
            if (isPanelXml) {
                // All .board.xml files in the project will be downloaded; check for any conflicts
                boolean anyExists = false;
                for (ProjectFile pf : project.files) {
                    if (pf.filename.toLowerCase().endsWith(".board.xml")
                            && new File(panelDir, pf.filename).exists()) {
                        anyExists = true;
                        break;
                    }
                }
                if (anyExists) {
                    int choice = JOptionPane.showConfirmDialog(frame,
                            Translations.getString("PartDb.Dialog.boardFileConflict.multipleMessage"),
                            Translations.getString("PartDb.Dialog.boardFileConflict.title"),
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    downloadBoardFiles = (choice == JOptionPane.YES_OPTION);
                } else {
                    downloadBoardFiles = true;
                }
            } else {
                File localBoardCheck = new File(panelDir, boardFileRecord.filename);
                if (localBoardCheck.exists()) {
                    int choice = JOptionPane.showConfirmDialog(frame,
                            String.format(Translations.getString("PartDb.Dialog.boardFileConflict.singleMessage"), boardFileRecord.filename),
                            Translations.getString("PartDb.Dialog.boardFileConflict.title"),
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE);
                    downloadBoardFiles = (choice == JOptionPane.YES_OPTION);
                } else {
                    downloadBoardFiles = true;
                }
            }

            importPanelFromProjectAction.setEnabled(false);
            importProgressBar.setValue(0);
            importProgressBar.setMaximum(1);
            importProgressBar.setString("0 / 0");
            importProgressBar.setVisible(true);

            new SwingWorker<File, int[]>() {
                @Override
                protected File doInBackground() throws Exception {
                    panelDir.mkdirs();

                    if (isPanelXml) {
                        // --- Import existing .panel.xml ---
                        // Collect board files from the project
                        List<ProjectFile> boardFiles = new ArrayList<>();
                        for (ProjectFile pf : project.files) {
                            if (pf.filename.toLowerCase().endsWith(".board.xml")) {
                                boardFiles.add(pf);
                            }
                        }

                        // Download board XMLs (skip existing if user chose to keep local)
                        for (ProjectFile bf : boardFiles) {
                            File localBoard = new File(panelDir, bf.filename);
                            if (downloadBoardFiles || !localBoard.exists()) {
                                Files.write(localBoard.toPath(),
                                        db.downloadFile(project.id, bf.id));
                            }
                        }

                        if (importMissingParts) {
                            // Collect all missing part IDs across all boards before loading
                            java.util.LinkedHashSet<String> missingIds =
                                    new java.util.LinkedHashSet<>();
                            for (ProjectFile bf : boardFiles) {
                                File localBoard = new File(panelDir, bf.filename);
                                Board tmp = Configuration.get().createSerializer()
                                        .read(Board.class, localBoard);
                                for (org.openpnp.model.Placement p : tmp.getPlacements()) {
                                    if (p.getPart() == null && p.getPartId() != null) {
                                        missingIds.add(p.getPartId());
                                    }
                                }
                            }
                            int total = missingIds.size();
                            publish(new int[]{0, total});
                            int current = 0;
                            for (String partId : missingIds) {
                                org.openpnp.gui.importer.PartDbProjectImporter
                                        .importOrCreatePart(partId, db);
                                publish(new int[]{++current, total});
                            }
                        }

                        // Pre-load boards so parts resolve correctly in loadPanel
                        for (ProjectFile bf : boardFiles) {
                            configuration.getBoard(new File(panelDir, bf.filename));
                        }

                        // Download panel XML
                        Files.write(panelFile.toPath(),
                                db.downloadFile(project.id, boardFileRecord.id));

                    } else {
                        // --- Create new panel from .board.xml ---
                        File localBoardFile = new File(panelDir, boardFileRecord.filename);
                        if (downloadBoardFiles) {
                            Files.write(localBoardFile.toPath(),
                                    db.downloadFile(project.id, boardFileRecord.id));
                        }

                        if (importMissingParts) {
                            Board tempBoard = Configuration.get().createSerializer()
                                    .read(Board.class, localBoardFile);
                            java.util.LinkedHashSet<String> missingIds =
                                    new java.util.LinkedHashSet<>();
                            for (org.openpnp.model.Placement p : tempBoard.getPlacements()) {
                                if (p.getPart() == null && p.getPartId() != null) {
                                    missingIds.add(p.getPartId());
                                }
                            }
                            int total = missingIds.size();
                            publish(new int[]{0, total});
                            int current = 0;
                            for (String partId : missingIds) {
                                org.openpnp.gui.importer.PartDbProjectImporter
                                        .importOrCreatePart(partId, db);
                                publish(new int[]{++current, total});
                            }
                        }

                        Board board = configuration.getBoard(localBoardFile);
                        Panel panel = new Panel();
                        panel.setName(panelName);
                        panel.setPartDbProjectId(project.id);
                        BoardLocation bl = new BoardLocation(board);
                        bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
                        panel.addChild(bl);
                        Configuration.get().createSerializer().write(panel, panelFile);
                        db.putFile(project.id, "OpenPnP Panel", panelFile.getName(),
                                Files.readAllBytes(panelFile.toPath()));
                    }
                    return panelFile;
                }

                @Override
                protected void process(List<int[]> chunks) {
                    int[] latest = chunks.get(chunks.size() - 1);
                    importProgressBar.setMaximum(Math.max(1, latest[1]));
                    importProgressBar.setValue(latest[0]);
                    importProgressBar.setString(latest[0] + " / " + latest[1]);
                }

                @Override
                protected void done() {
                    importProgressBar.setVisible(false);
                    importPanelFromProjectAction.setEnabled(true);
                    try {
                        get();
                        Panel panel = configuration.getPanel(panelFile);
                        if (!isPanelXml) {
                            panel.setName(panelName);
                        }
                        panel.setPartDbProjectId(project.id);
                        for (BoardLocation bl : panel.getDescendantBoardLocations()) {
                            Board b = bl.getBoard();
                            if (b != null && b.getPartDbProjectId() == null) {
                                b.setPartDbProjectId(project.id);
                                configuration.saveBoard(b);
                            }
                        }
                        panel.setDirty(false);
                        panelsTableModel.fireTableDataChanged();
                        selectPanel(panel);
                    } catch (Exception e) {
                        e.printStackTrace();
                        MessageBoxes.errorBox(frame, Translations.getString("PanelsPanel.Action.ImportPanelFromPartDB.failedTitle"),
                                e.getMessage());
                    }
                }
            }.execute();
        }
    };

    public final Action pushPanelToPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbPush);
            putValue(NAME, Translations.getString("PanelsPanel.Action.PushPanelToPartDB.name"));
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.PushPanelToPartDB.description"));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Panel panel = getSelection();
            if (panel == null || panel.getPartDbProjectId() == null) {
                return;
            }
            ProjectStorage db = PartDbDatabase.getProjectStorage();
            if (db == null || !db.isConnected()) {
                return;
            }
            try {
                if (panel.isDirty()) {
                    configuration.savePanel(panel);
                }
                String panelFileName = panel.getFile().getName();
                byte[] bytes = Files.readAllBytes(panel.getFile().toPath());
                db.putFile(panel.getPartDbProjectId(), "OpenPnP Panel", panelFileName, bytes);
                panel.setDirty(false);
                panelsTableModel.fireTableDataChanged();
            } catch (Exception e) {
                MessageBoxes.errorBox(frame, Translations.getString("PanelsPanel.Action.PushPanelToPartDB.failedTitle"), e.getMessage());
            }
        }
    };

    public final Action pullPanelFromPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbPull);
            putValue(NAME, Translations.getString("PanelsPanel.Action.PullPanelFromPartDB.name"));
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("PanelsPanel.Action.PullPanelFromPartDB.description"));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Panel panel = getSelection();
            if (panel == null || panel.getPartDbProjectId() == null) {
                return;
            }
            ProjectStorage db = PartDbDatabase.getProjectStorage();
            if (db == null || !db.isConnected()) {
                return;
            }
            final String projectId = panel.getPartDbProjectId();
            final File panelFile = panel.getFile();
            try {
                List<ProjectFile> projectFiles = db.getProjectFiles(projectId);
                // Download all board XMLs and the panel XML to the panel directory
                File panelDir = panelFile.getParentFile();
                for (ProjectFile pf : projectFiles) {
                    String lower = pf.filename.toLowerCase();
                    if (lower.endsWith(".board.xml") || lower.endsWith(".panel.xml")) {
                        byte[] bytes = db.downloadFile(projectId, pf.id);
                        Files.write(new File(panelDir, pf.filename).toPath(), bytes);
                    }
                }
                // Reload panel
                configuration.removePanel(panel);
                Panel reloaded = configuration.getPanel(panelFile);
                if (reloaded.getPartDbProjectId() == null) {
                    reloaded.setPartDbProjectId(projectId);
                }
                for (BoardLocation bl : reloaded.getDescendantBoardLocations()) {
                    Board b = bl.getBoard();
                    if (b != null && b.getPartDbProjectId() == null) {
                        b.setPartDbProjectId(projectId);
                        configuration.saveBoard(b);
                    }
                }
                reloaded.setDirty(false);
                panelsTableModel.fireTableDataChanged();
                selectPanel(reloaded);
            } catch (Exception e) {
                MessageBoxes.errorBox(frame, Translations.getString("PanelsPanel.Action.PullPanelFromPartDB.failedTitle"), e.getMessage());
            }
        }
    };

    protected Panel addPanel(File file) throws Exception {
        Panel panel = configuration.getPanel(file);
        // TODO: Move to a list property listener.
        panelsTableModel.fireTableDataChanged();
        return panel;
    }
    
    public final Action removePanelAction = new AbstractAction() { //$NON-NLS-1$
        {
            putValue(SMALL_ICON, Icons.delete);
            putValue(NAME, Translations.getString("PanelsPanel.Action.RemovePanel")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.RemovePanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            for (Panel selection : getSelections()) {
                if (configuration.isInUse(selection)) {
                    MessageBoxes.errorBox(PanelsPanel.this, 
                            Translations.getString("PanelsPanel.Action.RemovePanel.ErrorBox.Title"),  //$NON-NLS-1$
                            String.format(Translations.getString("PanelsPanel.Action.RemovePanel.ErrorBox.Message"), //$NON-NLS-1$
                                    selection.getName()));
                }
                else {
                    configuration.removePanel(selection);
                }
            }
            panelsTableModel.fireTableDataChanged();
        }
    };
    
    public final Action copyPanelAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.copy);
            putValue(NAME, Translations.getString("PanelsPanel.Action.CopyPanel")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.CopyPanel.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_COPY);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Panel panelToCopy = getSelection();
            FileDialog fileDialog = new FileDialog(frame, 
                    Translations.getString("PanelsPanel.Action.CopyPanel.SaveDialog"),  //$NON-NLS-1$
                    FileDialog.SAVE);
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".panel.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.panel.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                String filename = fileDialog.getFile();
                if (filename == null) {
                    return;
                }
                if (!filename.toLowerCase().endsWith(".panel.xml")) { //$NON-NLS-1$
                    filename = filename + ".panel.xml"; //$NON-NLS-1$
                }
                File file = new File(new File(fileDialog.getDirectory()), filename);

                Panel newPanel = new Panel(panelToCopy);
                newPanel.setDefinition(newPanel);
                newPanel.setFile(file);
                newPanel.setName(file.getName());
                newPanel.setDirty(false);
                configuration.addPanel(newPanel);
                configuration.savePanel(newPanel);
                panelsTableModel.fireTableDataChanged();
                selectPanel(newPanel);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(frame, 
                        Translations.getString("PanelsPanel.Action.CopyPanel.ErrorMessage"),  //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    public final Action cleanUpAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.clean);
            putValue(NAME, Translations.getString("PanelsPanel.Action.CleanUp")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PanelsPanel.Action.CleanUp.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_D);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            int endingCount = configuration.getPanels().size();
            int startingCount = endingCount + 1;
            //Because panels may be nested in panels, we need to repeat the cleanup until the
            //remaining list no longer changes size
            while (startingCount > endingCount) {
                startingCount = endingCount;
                List<Panel> panelsList = new ArrayList<>(configuration.getPanels());
                for (Panel panel : panelsList) {
                    if (!configuration.isInUse(panel)) {
                        configuration.removePanel(panel);
                        endingCount--;
                    }
                }
            }
        }
    };

}
