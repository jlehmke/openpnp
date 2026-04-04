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
import javax.swing.table.TableCellRenderer;
import javax.swing.border.TitledBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableRowSorter;

import org.openpnp.ConfigurationListener;
import org.openpnp.Translations;
import org.openpnp.events.PlacementsHolderLocationSelectedEvent;
import org.pmw.tinylog.Logger;
import org.openpnp.events.PlacementsHolderSelectedEvent;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.gui.support.ActionGroup;
import org.openpnp.gui.support.MonospacedFontTableCellRenderer;
import org.openpnp.gui.support.MultisortTableHeaderCellRenderer;
import org.openpnp.gui.support.TableUtils;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.LengthCellValue;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.tablemodel.PlacementsHolderTableModel;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.Configuration.TablesLinked;
import org.openpnp.machine.reference.PartDbDatabase;
import org.openpnp.model.ProjectFile;
import org.openpnp.model.ProjectRecord;
import org.openpnp.spi.Machine;
import org.openpnp.spi.ProjectStorage;
import com.google.common.eventbus.Subscribe;

@SuppressWarnings("serial")
public class BoardsPanel extends JPanel {
    final private Configuration configuration;
    final private MainFrame frame;

    private static final String PREF_DIVIDER_POSITION = "BoardsPanel.dividerPosition"; //$NON-NLS-1$
    private static final int PREF_DIVIDER_POSITION_DEF = -1;

    private PlacementsHolderTableModel boardsTableModel;
    private JTable boardsTable;
    private JSplitPane splitPane;

    private ActionGroup singleSelectionActionGroup;
    private ActionGroup multiSelectionActionGroup;

    private Preferences prefs = Preferences.userNodeForPackage(BoardsPanel.class);

    private final BoardPlacementsPanel boardPlacementsPanel;

    private ActionGroup partDbSelectionActionGroup;

    private JProgressBar importProgressBar;

    private Component partDbSeparator;
    private JButton btnImportFromPartDb;
    private JButton btnPullFromPartDb;
    private JButton btnPushToPartDb;

    private JPanel pnlPlacements;
    
    public BoardsPanel(Configuration configuration, MainFrame frame) {
        this.configuration = configuration;
        this.frame = frame;
        
        singleSelectionActionGroup = new ActionGroup(removeBoardAction, copyBoardAction);
        singleSelectionActionGroup.setEnabled(false);

        multiSelectionActionGroup = new ActionGroup(removeBoardAction);
        multiSelectionActionGroup.setEnabled(false);

        partDbSelectionActionGroup = new ActionGroup(pushBoardToPartDbAction, pullBoardFromPartDbAction);
        partDbSelectionActionGroup.setEnabled(false);
        
        boardsTableModel = new PlacementsHolderTableModel(configuration, 
                () -> configuration.getBoards(), Board.class);
        
        configuration.addPropertyChangeListener("boards", new PropertyChangeListener() { //$NON-NLS-1$
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                boardsTableModel.fireTableDataChanged();
            }
        });
        
        boardsTable = new AutoSelectTextTable(boardsTableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {

                java.awt.Point p = e.getPoint();
                int row = rowAtPoint(p);
                int col = columnAtPoint(p);

                if (row >= 0) {
                    if (col == 0) {
                        row = boardsTable.convertRowIndexToModel(row);
                        return configuration.getBoards().get(row).getFile().toString();
                    }
                }

                return super.getToolTipText();
            }

            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);
                if (!isRowSelected(row)) {
                    int modelRow = convertRowIndexToModel(row);
                    List<Board> boards = configuration.getBoards();
                    if (modelRow < boards.size()) {
                        Board b = boards.get(modelRow);
                        if (b.getPartDbProjectId() != null && b.isDirty()) {
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

        TableRowSorter<PlacementsHolderTableModel> boardsTableSorter = new TableRowSorter<>(boardsTableModel);
        boardsTable.setRowSorter(boardsTableSorter);
        boardsTable.getTableHeader().setDefaultRenderer(new MultisortTableHeaderCellRenderer());
        boardsTable.setDefaultRenderer(LengthCellValue.class, new MonospacedFontTableCellRenderer());
        boardsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        boardsTable.setAutoResizeMode(JTable.AUTO_RESIZE_NEXT_COLUMN);
        
        TableUtils.setColumnAlignment(boardsTableModel, boardsTable);
        
        TableUtils.installColumnWidthSavers(boardsTable, prefs, "BoardsPanel.boardsTable.columnWidth"); //$NON-NLS-1$
        
        boardsTable.getModel().addTableModelListener(new TableModelListener() {
            @Override
            public void tableChanged(TableModelEvent e) {
                SwingUtilities.invokeLater(() -> {
                    getBoardPlacementsPanel().refresh();
                });
            }
        });

        boardsTable.getSelectionModel()
                .addListSelectionListener(new ListSelectionListener() {
                    @Override
                    public void valueChanged(ListSelectionEvent e) {
                        if (e.getValueIsAdjusting()) {
                            return;
                        }
                        
                        boolean updateLinkedTables = 
                                MainFrame.get().getTabs().getSelectedComponent() == MainFrame.get().getBoardsTab() 
                                && Configuration.get().getTablesLinked() == TablesLinked.Linked;

                        List<Board> selections = getSelections();
                        if (selections.size() == 0) {
                            singleSelectionActionGroup.setEnabled(false);
                            multiSelectionActionGroup.setEnabled(false);
                            partDbSelectionActionGroup.setEnabled(false);
                            getBoardPlacementsPanel().setBoard(null);
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderSelectedEvent(null, BoardsPanel.this));
                            }
                        }
                        else if (selections.size() == 1) {
                            multiSelectionActionGroup.setEnabled(false);
                            singleSelectionActionGroup.setEnabled(true);
                            Board sel = selections.get(0);
                            partDbSelectionActionGroup.setEnabled(
                                    sel.getPartDbProjectId() != null && PartDbDatabase.getProjectStorage() != null);
                            getBoardPlacementsPanel().setBoard(sel);
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderSelectedEvent(selections.get(0), BoardsPanel.this));
                            }
                        }
                        else {
                            singleSelectionActionGroup.setEnabled(false);
                            multiSelectionActionGroup.setEnabled(true);
                            partDbSelectionActionGroup.setEnabled(false);
                            getBoardPlacementsPanel().setBoard(null);
                            if (updateLinkedTables) {
                                Configuration.get().getBus()
                                    .post(new PlacementsHolderSelectedEvent(null, BoardsPanel.this));
                            }
                        }
                        MainFrame.get().updateMenuState(BoardsPanel.this);
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

        JPanel pnlBoards = new JPanel();
        pnlBoards.setBorder(new TitledBorder(null,
                Translations.getString("BoardsPanel.Tab.Boards"), //$NON-NLS-1$
                TitledBorder.LEADING, TitledBorder.TOP, null));
        pnlBoards.setLayout(new BorderLayout(0, 0));

        JPanel toolbarRow = new JPanel(new BorderLayout());

        JToolBar toolBarBoards = new JToolBar();
        toolBarBoards.setFloatable(false);
        toolbarRow.add(toolBarBoards, BorderLayout.CENTER);

        importProgressBar = new JProgressBar();
        importProgressBar.setStringPainted(true);
        importProgressBar.setPreferredSize(new Dimension(150, importProgressBar.getPreferredSize().height));
        importProgressBar.setVisible(false);
        JPanel progressPanel = new JPanel();
        progressPanel.add(importProgressBar);
        toolbarRow.add(progressPanel, BorderLayout.EAST);

        pnlBoards.add(toolbarRow, BorderLayout.NORTH);

        JButton btnAddBoard = new JButton(addBoardAction);
        btnAddBoard.setHideActionText(true);
        btnAddBoard.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                JPopupMenu menu = new JPopupMenu();
                menu.add(new JMenuItem(addNewBoardAction));
                menu.add(new JMenuItem(addExistingBoardAction));
                menu.show(btnAddBoard, (int) btnAddBoard.getWidth(), (int) btnAddBoard.getHeight());
            }
        });
        toolBarBoards.add(btnAddBoard);
        
        JButton btnRemoveBoard = new JButton(removeBoardAction);
        btnRemoveBoard.setHideActionText(true);
        toolBarBoards.add(btnRemoveBoard);
        
        JButton btnCopyBoard = new JButton(copyBoardAction);
        btnCopyBoard.setHideActionText(true);
        toolBarBoards.add(btnCopyBoard);

        partDbSeparator = new JToolBar.Separator();
        toolBarBoards.add(partDbSeparator);

        btnImportFromPartDb = new JButton(importBoardFromProjectAction);
        btnImportFromPartDb.setHideActionText(true);
        toolBarBoards.add(btnImportFromPartDb);

        btnPullFromPartDb = new JButton(pullBoardFromPartDbAction);
        btnPullFromPartDb.setHideActionText(true);
        toolBarBoards.add(btnPullFromPartDb);

        btnPushToPartDb = new JButton(pushBoardToPartDbAction);
        btnPushToPartDb.setHideActionText(true);
        toolBarBoards.add(btnPushToPartDb);

        toolBarBoards.addSeparator();

        JButton btnCleanUp = new JButton(cleanUpAction);
        btnCleanUp.setHideActionText(true);
        toolBarBoards.add(btnCleanUp);
        
        pnlBoards.add(new JScrollPane(boardsTable));
        splitPane.setLeftComponent(pnlBoards);
        
        pnlPlacements = new JPanel();
        pnlPlacements.setLayout(new BorderLayout(0, 0));
        splitPane.setRightComponent(pnlPlacements);

        boardPlacementsPanel = new BoardPlacementsPanel(this);
        pnlPlacements.add(getBoardPlacementsPanel());
        
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

    public JTable getFiducialLocatableLocationsTable() {
        return boardsTable;
    }

    @Subscribe
    public void boardLocationSelected(PlacementsHolderLocationSelectedEvent event) {
        if (event.source == this || event.placementsHolderLocation == null || !(event.placementsHolderLocation.getPlacementsHolder() instanceof Board)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            selectBoard((Board) event.placementsHolderLocation.getPlacementsHolder().getDefinition());
        });
    }

    public void selectBoard(Board board) {
        if (board == null) {
            boardsTable.getSelectionModel().clearSelection();
            return;
        }
        int index = boardsTableModel.indexOf(board);
        if (index >= 0) {
            index = boardsTable.convertRowIndexToView(index);
            boardsTable.getSelectionModel().setSelectionInterval(index, index);
            boardsTable.scrollRectToVisible(
                    new Rectangle(boardsTable.getCellRect(index, 0, true)));
        }
    }

    public void refresh() {
        boardsTableModel.fireTableDataChanged();
    }

    public void refreshSelectedRow() {
        int index = boardsTable.convertRowIndexToModel(boardsTable.getSelectedRow());
        boardsTableModel.fireTableRowsUpdated(index, index);
    }

    public Board getSelection() {
        List<Board> selections = getSelections();
        if (selections.isEmpty()) {
            return null;
        }
        return selections.get(0);
    }

    public List<Board> getSelections() {
        ArrayList<Board> selections = new ArrayList<>();
        int[] selectedRows = boardsTable.getSelectedRows();
        for (int selectedRow : selectedRows) {
            selectedRow = boardsTable.convertRowIndexToModel(selectedRow);
            selections.add(configuration.getBoards().get(selectedRow));
        }
        return selections;
    }

    public final Action addBoardAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("BoardsPanel.Action.AddBoard")); //$NON-NLS-1$
            putValue(SMALL_ICON, Icons.add);
            putValue(SHORT_DESCRIPTION, Translations.getString("BoardsPanel.Action.AddBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_A);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {}
    };

    public final Action addNewBoardAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("BoardsPanel.Action.AddBoard.NewBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("BoardsPanel.Action.AddBoard.NewBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_N);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            FileDialog fileDialog = new FileDialog(frame, 
                    Translations.getString("BoardsPanel.Action.AddBoard.NewBoard.SaveDialog"), FileDialog.SAVE); //$NON-NLS-1$
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".board.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.board.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                String filename = fileDialog.getFile();
                if (filename == null) {
                    return;
                }
                if (!filename.toLowerCase().endsWith(".board.xml")) { //$NON-NLS-1$
                    filename = filename + ".board.xml"; //$NON-NLS-1$
                }
                File file = new File(new File(fileDialog.getDirectory()), filename);

                Board board = addBoard(file);

                selectBoard(board);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(frame, 
                        Translations.getString("BoardsPanel.Action.AddBoard.NewBoard.ErrorMessage"), e.getMessage()); //$NON-NLS-1$
            }
        }
    };

    public final Action addExistingBoardAction = new AbstractAction() {
        {
            putValue(NAME, Translations.getString("BoardsPanel.Action.AddBoard.ExistingBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("BoardsPanel.Action.AddBoard.ExistingBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_E);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            FileDialog fileDialog = new FileDialog(frame);
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".board.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.board.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                if (fileDialog.getFile() == null) {
                    return;
                }
                File file = new File(new File(fileDialog.getDirectory()), fileDialog.getFile());

                Board board = addBoard(file);

                selectBoard(board);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(frame, 
                        Translations.getString("BoardsPanel.Action.AddBoard.ExistingBoard.ErrorMessage"), //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    protected Board addBoard(File file) throws Exception {
        Board board = configuration.getBoard(file);
        // TODO: Move to a list property listener.
        boardsTableModel.fireTableDataChanged();
        return board;
    }
    
    public BoardPlacementsPanel getBoardPlacementsPanel() {
        return boardPlacementsPanel;
    }

    public final Action removeBoardAction = new AbstractAction("Remove Board") { //$NON-NLS-1$
        {
            putValue(SMALL_ICON, Icons.delete);
            putValue(NAME, Translations.getString("BoardsPanel.Action.RemoveBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("BoardsPanel.Action.RemoveBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_R);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            for (Board selection : getSelections()) {
                if (configuration.isInUse(selection)) {
                    MessageBoxes.errorBox(BoardsPanel.this, 
                            Translations.getString("BoardsPanel.Action.RemoveBoard.ErrorBox.Title"), //$NON-NLS-1$
                            String.format(Translations.getString("BoardsPanel.Action.RemoveBoard.ErrorBox.MessageFormat"), //$NON-NLS-1$
                                    selection.getName()));
                }
                else {
                    configuration.removeBoard(selection);
                }
            }
            boardsTableModel.fireTableDataChanged();
        }
    };
    
    public final Action copyBoardAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.copy);
            putValue(NAME, Translations.getString("BoardsPanel.Action.CopyBoard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, 
                    Translations.getString("BoardsPanel.Action.CopyBoard.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_COPY);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Board boardToCopy = getSelection();
            FileDialog fileDialog = new FileDialog(frame, 
                    Translations.getString("BoardsPanel.Action.CopyBoard.SaveDialog"), FileDialog.SAVE); //$NON-NLS-1$
            fileDialog.setFilenameFilter(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return name.toLowerCase().endsWith(".board.xml"); //$NON-NLS-1$
                }
            });
            fileDialog.setFile("*.board.xml"); //$NON-NLS-1$
            fileDialog.setVisible(true);
            try {
                String filename = fileDialog.getFile();
                if (filename == null) {
                    return;
                }
                if (!filename.toLowerCase().endsWith(".board.xml")) { //$NON-NLS-1$
                    filename = filename + ".board.xml"; //$NON-NLS-1$
                }
                File file = new File(new File(fileDialog.getDirectory()), filename);

                Board newBoard = new Board(boardToCopy);
                newBoard.setDefinition(newBoard);
                newBoard.setFile(file);
                newBoard.setName(file.getName());
                newBoard.setDirty(false);
                configuration.addBoard(newBoard);
                configuration.saveBoard(newBoard);
                boardsTableModel.fireTableDataChanged();
                selectBoard(newBoard);
            }
            catch (Exception e) {
                e.printStackTrace();
                MessageBoxes.errorBox(frame, 
                        Translations.getString("BoardsPanel.Action.CopyBoard.ErrorMessage"), //$NON-NLS-1$
                        e.getMessage());
            }
        }
    };

    public final Action importBoardFromProjectAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbAdd);
            putValue(NAME, Translations.getString("BoardsPanel.Action.ImportBoardFromPartDB.name"));
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("BoardsPanel.Action.ImportBoardFromPartDB.description"));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            ProjectStorage db = PartDbDatabase.getProjectStorage();
            if (db == null || !db.isConnected()) {
                javax.swing.JOptionPane.showMessageDialog(frame,
                        Translations.getString("PartDb.Dialog.noProjectStorage.message"),
                        Translations.getString("PartDb.Dialog.noProjectStorage.title"), javax.swing.JOptionPane.WARNING_MESSAGE);
                return;
            }

            ProjectBoardImportDialog dlg = new ProjectBoardImportDialog(frame, db, false);
            dlg.setVisible(true);

            ProjectRecord project = dlg.getSelectedProject();
            ProjectFile posFile = dlg.getSelectedFile();
            if (project == null) {
                return;
            }
            if (posFile == null && !dlg.isCreateMode()) {
                return;
            }

            // Ask the user where to save the board file
            FileDialog fileDialog = new FileDialog(frame,
                    Translations.getString("BoardsPanel.Action.ImportBoardFromPartDB.saveDialogTitle"), FileDialog.SAVE);
            fileDialog.setFilenameFilter((dir, name) -> name.toLowerCase().endsWith(".board.xml"));
            fileDialog.setFile(project.name.replaceAll("[^a-zA-Z0-9._-]", "_") + ".board.xml");
            fileDialog.setVisible(true);
            if (fileDialog.getFile() == null) {
                return;
            }
            String filename = fileDialog.getFile();
            if (!filename.toLowerCase().endsWith(".board.xml")) {
                filename = filename + ".board.xml";
            }
            File boardFile = new File(fileDialog.getDirectory(), filename);

            final boolean importMissingParts = dlg.isImportMissingParts();
            final boolean isBoardXml = posFile != null
                    && posFile.filename.toLowerCase().endsWith(".board.xml");
            final boolean alreadyOnRemote = isBoardXml;

            // Derive board name before starting background work
            String fileStem = boardFile.getName();
            if (fileStem.toLowerCase().endsWith(".board.xml")) {
                fileStem = fileStem.substring(0, fileStem.length() - ".board.xml".length());
            }
            final String projectStem = project.name.replaceAll("[^a-zA-Z0-9._-]", "_");
            final String boardName = (!fileStem.equals(projectStem)
                    && !fileStem.equals(project.name))
                    ? project.name + " [" + fileStem + "]"
                    : project.name;

            importBoardFromProjectAction.setEnabled(false);
            importProgressBar.setValue(0);
            importProgressBar.setMaximum(1);
            importProgressBar.setString("0 / 0");
            importProgressBar.setVisible(true);

            new SwingWorker<Board, int[]>() {
                @Override
                protected Board doInBackground() throws Exception {
                    Board board;
                    if (posFile == null) {
                        // Create mode — empty board, no I/O needed
                        board = new Board();
                    } else if (isBoardXml) {
                        // Pull existing board XML from PartDB
                        byte[] bytes = db.downloadFile(project.id, posFile.id);
                        File tmp = File.createTempFile("openpnp-partdb-", ".board.xml");
                        tmp.deleteOnExit();
                        Files.write(tmp.toPath(), bytes);
                        board = Configuration.get().createSerializer().read(Board.class, tmp);
                        if (importMissingParts) {
                            List<org.openpnp.model.Placement> missing = new ArrayList<>();
                            for (org.openpnp.model.Placement p : board.getPlacements()) {
                                if (p.getPart() == null && p.getPartId() != null) {
                                    missing.add(p);
                                }
                            }
                            int total = missing.size();
                            publish(new int[]{0, total});
                            for (int i = 0; i < total; i++) {
                                org.openpnp.model.Placement p = missing.get(i);
                                p.setPart(org.openpnp.gui.importer.PartDbProjectImporter
                                        .importOrCreatePart(p.getPartId(), db));
                                publish(new int[]{i + 1, total});
                            }
                        }
                    } else {
                        // Placement file (.pos, .csv, …) — import via BOM + importer
                        org.openpnp.gui.importer.PartDbProjectImporter importer =
                                new org.openpnp.gui.importer.PartDbProjectImporter();
                        board = importer.importBoardFromProjectStorage(frame, project, posFile,
                                db, importMissingParts, (current, total) ->
                                        publish(new int[]{current, total}));
                        if (board == null) {
                            return null;
                        }
                    }
                    board.setName(boardName);
                    board.setPartDbProjectId(project.id);
                    board.setFile(boardFile);
                    Configuration.get().createSerializer().write(board, boardFile);
                    return board;
                }

                @Override
                protected void process(List<int[]> chunks) {
                    int[] latest = chunks.get(chunks.size() - 1);
                    int current = latest[0];
                    int total = latest[1];
                    importProgressBar.setMaximum(Math.max(1, total));
                    importProgressBar.setValue(current);
                    importProgressBar.setString(current + " / " + total);
                }

                @Override
                protected void done() {
                    importProgressBar.setVisible(false);
                    importBoardFromProjectAction.setEnabled(true);
                    try {
                        Board board = get();
                        if (board == null) {
                            return;
                        }
                        board.setDirty(!alreadyOnRemote);
                        configuration.addBoard(board);
                        boardsTableModel.fireTableDataChanged();
                        selectBoard(board);
                    } catch (Exception e) {
                        e.printStackTrace();
                        MessageBoxes.errorBox(frame, Translations.getString("BoardsPanel.Action.ImportBoardFromPartDB.failedTitle"),
                                e.getMessage());
                    }
                }
            }.execute();
        }
    };

    public final Action pushBoardToPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbPush);
            putValue(NAME, Translations.getString("BoardsPanel.Action.PushBoardToPartDB.name"));
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("BoardsPanel.Action.PushBoardToPartDB.description"));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Board board = getSelection();
            if (board == null || board.getPartDbProjectId() == null) {
                return;
            }
            ProjectStorage db = PartDbDatabase.getProjectStorage();
            if (db == null || !db.isConnected()) {
                return;
            }
            try {
                if (board.isDirty()) {
                    configuration.saveBoard(board);
                }
                String boardFileName = board.getFile().getName();
                byte[] bytes = Files.readAllBytes(board.getFile().toPath());
                db.putFile(board.getPartDbProjectId(), "OpenPnP Board", boardFileName, bytes);
                board.setDirty(false);
                boardsTableModel.fireTableDataChanged();
                Logger.info("PartDB: pushed board '{}' to project {}",
                        boardFileName, board.getPartDbProjectId());
            } catch (Exception e) {
                MessageBoxes.errorBox(frame, Translations.getString("BoardsPanel.Action.PushBoardToPartDB.failedTitle"), e.getMessage());
            }
        }
    };

    public final Action pullBoardFromPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbPull);
            putValue(NAME, Translations.getString("BoardsPanel.Action.PullBoardFromPartDB.name"));
            putValue(SHORT_DESCRIPTION,
                    Translations.getString("BoardsPanel.Action.PullBoardFromPartDB.description"));
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Board board = getSelection();
            if (board == null || board.getPartDbProjectId() == null) {
                return;
            }
            ProjectStorage db = PartDbDatabase.getProjectStorage();
            if (db == null || !db.isConnected()) {
                return;
            }
            try {
                String projectId = board.getPartDbProjectId();
                // Find the OpenPnP Board attachment in the project
                List<ProjectFile> files = db.getProjectFiles(projectId);
                ProjectFile boardAttachment = null;
                for (ProjectFile f : files) {
                    if (f.filename.toLowerCase().endsWith(".board.xml")) {
                        boardAttachment = f;
                        break;
                    }
                }
                if (boardAttachment == null) {
                    MessageBoxes.errorBox(frame, Translations.getString("BoardsPanel.Action.PullBoardFromPartDB.failedTitle"),
                            String.format(Translations.getString("BoardsPanel.Action.PullBoardFromPartDB.noAttachment"), projectId));
                    return;
                }
                byte[] bytes = db.downloadFile(projectId, boardAttachment.id);
                File boardFile = board.getFile();
                // Remove old board, overwrite file, reload
                configuration.removeBoard(board);
                Files.write(boardFile.toPath(), bytes);
                Board reloaded = (Board) configuration.getBoard(boardFile);
                // Re-attach project ID in case it was missing in the downloaded XML
                if (reloaded.getPartDbProjectId() == null) {
                    reloaded.setPartDbProjectId(projectId);
                    configuration.saveBoard(reloaded);
                }
                boardsTableModel.fireTableDataChanged();
                selectBoard(reloaded);
            } catch (Exception e) {
                MessageBoxes.errorBox(frame, Translations.getString("BoardsPanel.Action.PullBoardFromPartDB.failedTitle"), e.getMessage());
            }
        }
    };

    public final Action cleanUpAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.clean);
            putValue(NAME, Translations.getString("BoardsPanel.Action.CleanUp")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("BoardsPanel.Action.CleanUp.Description")); //$NON-NLS-1$
            putValue(MNEMONIC_KEY, KeyEvent.VK_D);
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<Board> boardsList = new ArrayList<>(configuration.getBoards());
            for (Board board : boardsList) {
                if (!configuration.isInUse(board)) {
                    configuration.removeBoard(board);
                }
            }
        }
    };

}
