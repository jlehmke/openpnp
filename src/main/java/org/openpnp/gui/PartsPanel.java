/*
 * Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import java.awt.Color;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import javax.swing.RowFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

import org.openpnp.ConfigurationListener;
import org.openpnp.Translations;
import org.openpnp.gui.components.AutoSelectTextTable;
import org.openpnp.machine.reference.PartDbDatabase;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.ActionGroup;
import org.openpnp.gui.support.Helpers;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.gui.support.IdentifiableTableCellRenderer;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.MultisortTableHeaderCellRenderer;
import org.openpnp.gui.support.NamedListCellRenderer;
import org.openpnp.gui.support.NamedTableCellRenderer;
import org.openpnp.gui.support.PackagesComboBoxModel;
import org.openpnp.gui.support.VisionSettingsComboBoxModel;
import org.openpnp.gui.support.Wizard;
import org.openpnp.gui.support.WizardContainer;
import org.openpnp.gui.tablemodel.PartsTableModel;
import org.openpnp.gui.wizards.PartSettingsWizard;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.BottomVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.model.Configuration.TablesLinked;
import org.openpnp.model.FiducialVisionSettings;
import org.openpnp.model.Part;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.PartDatabase;
import org.openpnp.spi.FiducialLocator;
import org.openpnp.spi.PartAlignment;
import org.openpnp.util.UiUtils;
import org.openpnp.util.FeederUtils;
import org.pmw.tinylog.Logger;
import org.simpleframework.xml.Serializer;

@SuppressWarnings("serial")
public class PartsPanel extends JPanel implements WizardContainer {


    private static final String PREF_DIVIDER_POSITION = "PartsPanel.dividerPosition";
    private static final int PREF_DIVIDER_POSITION_DEF = -1;
    private Preferences prefs = Preferences.userNodeForPackage(PartsPanel.class);

    final private Configuration configuration;
    final private Frame frame;

    private PartsTableModel tableModel;
    private TableRowSorter<PartsTableModel> tableSorter;
    private JTextField searchTextField;
    private JTable table;
    private ActionGroup singleSelectionActionGroup;
    private ActionGroup multiSelectionActionGroup;
    private JTabbedPane tabbedPane;
    private Part selectedPart;
    private int priorRowIndex = -1;
    private HashMap<Class, Integer> lastSelectedTabIndex = new HashMap<>();
    private TableColumn stockColumn;
    private JButton flushStockBtn;
    private JProgressBar stockRefreshBar;
    private Component partDbSeparator;
    private JButton btnImportFromPartDb;
    private JButton btnUpdateFromPartDb;
    private JButton btnPushToPartDb;

    public PartsPanel(Configuration configuration, Frame frame) {
        this.configuration = configuration;
        this.frame = frame;

        singleSelectionActionGroup = new ActionGroup(deletePartAction, pickPartAction, copyPartToClipboardAction,
                updateFromPartDbAction, pushToPartDbAction);
        singleSelectionActionGroup.setEnabled(false);
        multiSelectionActionGroup = new ActionGroup(deletePartAction, updateFromPartDbAction, pushToPartDbAction);
        multiSelectionActionGroup.setEnabled(false);

        setLayout(new BorderLayout(0, 0));
        tableModel = new PartsTableModel();
        tableSorter = new TableRowSorter<>(tableModel);

        JPanel toolbarAndSearch = new JPanel();
        add(toolbarAndSearch, BorderLayout.NORTH);
        toolbarAndSearch.setLayout(new BorderLayout(0, 0));

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolbarAndSearch.add(toolBar);

        JPanel panel_1 = new JPanel();
        toolbarAndSearch.add(panel_1, BorderLayout.EAST);

        stockRefreshBar = new JProgressBar();
        stockRefreshBar.setStringPainted(true);
        stockRefreshBar.setPreferredSize(new Dimension(150, stockRefreshBar.getPreferredSize().height));
        stockRefreshBar.setVisible(false);
        panel_1.add(stockRefreshBar);

        JLabel lblSearch = new JLabel(Translations.getString("PartsPanel.SearchLabel.text")); //$NON-NLS-1$
        panel_1.add(lblSearch);

        searchTextField = new JTextField();
        searchTextField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void removeUpdate(DocumentEvent e) {
                search();
            }

            @Override
            public void insertUpdate(DocumentEvent e) {
                search();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                search();
            }
        });
        panel_1.add(searchTextField);
        searchTextField.setColumns(15);

        JComboBox packagesCombo = new JComboBox(new PackagesComboBoxModel());
        packagesCombo.setMaximumRowCount(20);
        packagesCombo.setRenderer(new IdentifiableListCellRenderer<org.openpnp.model.Package>());

        JSplitPane splitPane = new JSplitPane();
        splitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        splitPane.setContinuousLayout(true);
        splitPane
                .setDividerLocation(prefs.getInt(PREF_DIVIDER_POSITION, PREF_DIVIDER_POSITION_DEF));
        splitPane.addPropertyChangeListener("dividerLocation", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                prefs.putInt(PREF_DIVIDER_POSITION, splitPane.getDividerLocation());
            }
        });
        add(splitPane, BorderLayout.CENTER);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.addChangeListener(e -> {
            if (!rebuildingTabs) {
                Component selected = tabbedPane.getSelectedComponent();
                if (selected instanceof PartDbDetailsPanel) {
                    ((PartDbDetailsPanel) selected).loadIfNeeded();
                }
            }
        });

        table = new AutoSelectTextTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                int column = convertColumnIndexToModel(columnAtPoint(evt.getPoint()));
                if(column==2) { return Translations.getString("PartsTableModel.Column.Height.toolTip"); } //$NON-NLS-1$
                if(column==3) { return Translations.getString("PartsTableModel.Column.ThroughBoardDepth.toolTip"); } //$NON-NLS-1$
                return null;
            }
        };
        // Cancel any active cell edit before the model fires structural changes,
        // otherwise DefaultRowSorter throws IndexOutOfBoundsException.
        tableModel.addTableModelListener(e -> {
            if (table.isEditing()) {
                table.removeEditor();
            }
        });
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDefaultEditor(org.openpnp.model.Package.class,
                new DefaultCellEditor(packagesCombo));
        table.setDefaultRenderer(org.openpnp.model.Package.class,
                new IdentifiableTableCellRenderer<org.openpnp.model.Package>());

        JComboBox<BottomVisionSettings> bottomVisionCombo = new JComboBox<>(
                new VisionSettingsComboBoxModel(BottomVisionSettings.class));
        bottomVisionCombo.setMaximumRowCount(20);
        bottomVisionCombo.setRenderer(new NamedListCellRenderer<>());
        table.setDefaultEditor(BottomVisionSettings.class,
                new DefaultCellEditor(bottomVisionCombo));

        JComboBox<FiducialVisionSettings> fiducialVisionCombo = new JComboBox<>(
                new VisionSettingsComboBoxModel(FiducialVisionSettings.class));
        fiducialVisionCombo.setMaximumRowCount(20);
        fiducialVisionCombo.setRenderer(new NamedListCellRenderer<>());
        table.setDefaultEditor(FiducialVisionSettings.class,
                new DefaultCellEditor(fiducialVisionCombo));

        table.setDefaultRenderer(AbstractVisionSettings.class,
                new NamedTableCellRenderer<AbstractVisionSettings>());

        table.setRowSorter(tableSorter);
        table.getTableHeader().setDefaultRenderer(new MultisortTableHeaderCellRenderer());
        splitPane.setLeftComponent(new JScrollPane(table));
        splitPane.setRightComponent(tabbedPane);
        
        toolBar.add(newPartAction);
        toolBar.add(deletePartAction);
        toolBar.addSeparator();
        toolBar.add(pickPartAction);
        
        toolBar.addSeparator();
        JButton btnNewButton = new JButton(copyPartToClipboardAction);
        btnNewButton.setHideActionText(true);
        toolBar.add(btnNewButton);
        
        JButton btnNewButton_1 = new JButton(pastePartToClipboardAction);
        btnNewButton_1.setHideActionText(true);
        toolBar.add(btnNewButton_1);

        partDbSeparator = new JToolBar.Separator();
        toolBar.add(partDbSeparator);
        btnImportFromPartDb = (JButton) toolBar.add(importFromPartDbAction);
        btnUpdateFromPartDb = (JButton) toolBar.add(updateFromPartDbAction);
        btnPushToPartDb = (JButton) toolBar.add(pushToPartDbAction);
        flushStockBtn = new JButton(Icons.partDbSync);
        flushStockBtn.setToolTipText("Sync stock: push pending placements and refresh from database");
        flushStockBtn.setVisible(false);
        flushStockBtn.setEnabled(false);
        flushStockBtn.addActionListener(e -> {
            if (partDb != null) {
                flushStockBtn.setEnabled(false);
                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        partDb.flushPlacements();
                        partDb.refreshStockLevels();
                        return null;
                    }
                    @Override
                    protected void done() {
                        flushStockBtn.setEnabled(true);
                        try {
                            get();
                        } catch (Exception ex) {
                            UiUtils.showError(ex);
                        }
                    }
                }.execute();
            }
        });
        toolBar.add(flushStockBtn);

        table.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (e.getValueIsAdjusting()) {
                    return;
                }
                if (table.getSelectedRow() != priorRowIndex) {
                    priorRowIndex = table.getSelectedRow();

                    updateWizards();
                }
            }
        });
        
        Configuration.get().addPropertyChangeListener("visionSettings", new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                // Handle vision settings changes like selection changes, as the inherited settings might change. 
                updateWizards();
            }
        });

        tableModel.addTableModelListener(e -> {
            if (selectedPart != null && getSelectedPart() != selectedPart) {
                // Reselect previously selected settings.
                Helpers.selectObjectTableRow(table, selectedPart);
            }
        });

        // Save stock column object and hide it immediately; set up PartDB listeners once
        // the machine is available (deferred so configuration is fully loaded).
        stockColumn = table.getColumnModel().getColumn(10);
        table.removeColumn(stockColumn);
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
        SwingUtilities.invokeLater(this::setupPartDbStockColumn);
    }

    private void updatePartDbVisibility() {
        boolean enabled = PartDbDatabase.getProjectStorage() != null;
        partDbSeparator.setVisible(enabled);
        btnImportFromPartDb.setVisible(enabled);
        btnUpdateFromPartDb.setVisible(enabled);
        btnPushToPartDb.setVisible(enabled);
    }

    private boolean stockColumnShown = false;

    private PartDbDatabase partDb;

    private void setupPartDbStockColumn() {
        Machine machine = configuration.getMachine();
        if (!(machine instanceof ReferenceMachine)) {
            return;
        }
        PartDatabase db = ((ReferenceMachine) machine).getPartDatabase();
        if (!(db instanceof PartDbDatabase)) {
            return;
        }
        partDb = (PartDbDatabase) db;

        // Yellow background on stock cell when that part has pending (unflushed) placements.
        stockColumn.setCellRenderer(new DefaultTableCellRenderer() {
            private final Color pendingColor = new Color(255, 230, 80);
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value,
                    boolean selected, boolean focus, int viewRow, int col) {
                super.getTableCellRendererComponent(tbl, value, selected, focus, viewRow, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                if (!selected) {
                    if (partDb != null) {
                        int modelRow = tbl.convertRowIndexToModel(viewRow);
                        Part rowPart = tableModel.getRowObjectAt(modelRow);
                        if (rowPart != null && partDb.getPendingCount(rowPart.getId()) > 0) {
                            setBackground(pendingColor);
                            return this;
                        }
                    }
                }
                return this;
            }
        });

        partDb.addPropertyChangeListener("connected", evt ->
                SwingUtilities.invokeLater(() -> {
                    updateStockColumnVisibility();
                    updateFlushButton();
                }));
        partDb.addPropertyChangeListener("enabled", evt ->
                SwingUtilities.invokeLater(() -> {
                    updateStockColumnVisibility();
                    updateFlushButton();
                }));
        partDb.addPropertyChangeListener("trackPlacements", evt ->
                SwingUtilities.invokeLater(this::updateStockColumnVisibility));
        partDb.addPropertyChangeListener("hideStockLevel", evt ->
                SwingUtilities.invokeLater(this::updateStockColumnVisibility));
        partDb.addPropertyChangeListener("readOnly", evt ->
                SwingUtilities.invokeLater(this::updateFlushButton));
        partDb.addPropertyChangeListener("disableAutoFlushOnJobFinish", evt ->
                SwingUtilities.invokeLater(this::updateFlushButton));
        partDb.addPropertyChangeListener("disableAutoFlushOnShutdown", evt ->
                SwingUtilities.invokeLater(this::updateFlushButton));
        partDb.addPropertyChangeListener("stockLevels", evt ->
                SwingUtilities.invokeLater(() -> {
                    Part saved = selectedPart;
                    tableModel.fireTableDataChanged();
                    if (saved != null) {
                        for (int i = 0; i < tableModel.getRowCount(); i++) {
                            if (tableModel.getRowObjectAt(i) == saved) {
                                int viewRow = table.convertRowIndexToView(i);
                                table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
                                break;
                            }
                        }
                    }
                }));
        partDb.addPropertyChangeListener("pendingPlacements", evt ->
                SwingUtilities.invokeLater(tableModel::fireTableDataChanged));
        partDb.addPropertyChangeListener("stockRefreshProgress", evt ->
                SwingUtilities.invokeLater(() -> {
                    int progress = (Integer) evt.getNewValue();
                    if (progress < 0) {
                        stockRefreshBar.setVisible(false);
                    } else {
                        stockRefreshBar.setMaximum(partDb.getStockRefreshTotal());
                        stockRefreshBar.setValue(progress);
                        stockRefreshBar.setString(progress + " / " + partDb.getStockRefreshTotal());
                        stockRefreshBar.setVisible(true);
                    }
                }));

        updateStockColumnVisibility();
        updateFlushButton();
    }

    private void updateStockColumnVisibility() {
        if (partDb != null && partDb.isEnabled() && partDb.isConnected()
                && partDb.isTrackPlacements() && !partDb.isHideStockLevel()) {
            showStockColumn();
        } else {
            hideStockColumn();
        }
    }

    private void updateFlushButton() {
        boolean visible = partDb != null && partDb.isEnabled();
        flushStockBtn.setVisible(visible);
        flushStockBtn.setEnabled(visible && partDb.isConnected() && !partDb.isReadOnly());
    }

    private void showStockColumn() {
        if (!stockColumnShown) {
            table.addColumn(stockColumn);
            stockColumnShown = true;
        }
    }

    private void hideStockColumn() {
        if (stockColumnShown) {
            table.removeColumn(stockColumn);
            stockColumnShown = false;
        }
    }

    public Part getSelectedPart() {
        List<Part> selections = getSelections();
        if (selections.size() != 1) {
            return null;
        }
        return selections.get(0);
    }

    private List<Part> getSelections() {
        List<Part> selections = new ArrayList<>();
        for (int selectedRow : table.getSelectedRows()) {
            selectedRow = table.convertRowIndexToModel(selectedRow);
            try {
                selections.add(tableModel.getRowObjectAt(selectedRow));
            }
            catch (IndexOutOfBoundsException e) {
                // sometimes this happens when deleting a row, if the gui state
                // updates after the model state
                Logger.warn("part selection index {} out of bounds", selectedRow);
            }
        }
        return selections;
    }

    private void search() {
        RowFilter<PartsTableModel, Object> rf = null;
        // If current expression doesn't parse, don't update.
        try {
            rf = RowFilter.regexFilter("(?i)" + searchTextField.getText().trim());
        }
        catch (PatternSyntaxException e) {
            Logger.warn(e, "Search failed");
            return;
        }
        tableSorter.setRowFilter(rf);
    }

    public final Action newPartAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.add);
            putValue(NAME, Translations.getString("PartsPanel.Action.NewPart")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PartsPanel.Action.NewPart.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            if (Configuration.get().getPackages().size() == 0) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Error",
                        "There are currently no packages defined in the system. Please create at least one package before creating a part.");
                return;
            }

            String id;
            while ((id = JOptionPane.showInputDialog(frame,
                    "Please enter an ID for the new part.")) != null) {
                id = id.trim();
                if (id.isEmpty()) {
                    break;
                }
                if (configuration.getPart(id) != null) {
                    MessageBoxes.errorBox(frame, "Error", "Part ID " + id + " already exists.");
                    continue;
                }
                Part part = new Part(id);

                part.setPackage(Configuration.get().getPackages().get(0));

                configuration.addPart(part);
                tableModel.fireTableDataChanged();
                Helpers.selectObjectTableRow(table, part);
                break;
            }
        }
    };

    public final Action deletePartAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.delete);
            putValue(NAME, Translations.getString("PartsPanel.Action.DeletePart")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PartsPanel.Action.DeletePart.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            List<Part> selections = getSelections();
            List<String> ids = selections.stream().map(Part::getId).collect(Collectors.toList());
            String formattedIds;
            if (ids.size() <= 3) {
                formattedIds = String.join(", ", ids);
            }
            else {
                formattedIds = String.join(", ", ids.subList(0, 3)) + ", and " + (ids.size() - 3) + " others";
            }
            
            int ret = JOptionPane.showConfirmDialog(getTopLevelAncestor(),
                    Translations.getString("DialogMessages.ConfirmDelete.text") + " " + formattedIds + "?", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    Translations.getString("DialogMessages.ConfirmDelete.title") + " " + selections.size() + " " + Translations.getString( //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                                    "CommonWords.parts") + "?", JOptionPane.YES_NO_OPTION); //$NON-NLS-1$ //$NON-NLS-2$
            if (ret == JOptionPane.YES_OPTION) {
                for (Part part : selections) {
                    Configuration.get().removePart(part);
                }
            }
        }
    };

    public final Action pickPartAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.pick);
            putValue(NAME, Translations.getString("PartsPanel.Action.PickPart")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PartsPanel.Action.PickPart.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            UiUtils.submitUiMachineTask(() -> {
                Part part = getSelectedPart();
                Feeder feeder = FeederUtils.findFeeder(Configuration.get().getMachine(),part,null,null);
                if (feeder == null) {
                    throw new Exception("No valid feeder found for " + part.getId());
                }
                // Perform the whole Job like pick cycle as in the FeedersPanel. 
                FeedersPanel.pickFeeder(feeder);
            });
        }
    };

    public final Action copyPartToClipboardAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.copy);
            putValue(NAME, Translations.getString("PartsPanel.Action.CopyPartToClipboard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PartsPanel.Action.CopyPartToClipboard.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            Part part = getSelectedPart();
            if (part == null) {
                return;
            }
            try {
                Serializer s = Configuration.createSerializer();
                StringWriter w = new StringWriter();
                s.write(part, w);
                StringSelection stringSelection = new StringSelection(w.toString());
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(stringSelection, null);
            }
            catch (Exception e) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Copy Failed", e);
            }
        }
    };

    public final Action pastePartToClipboardAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.paste);
            putValue(NAME, Translations.getString("PartsPanel.Action.PastePartFromClipboard")); //$NON-NLS-1$
            putValue(SHORT_DESCRIPTION, Translations.getString("PartsPanel.Action.PastePartFromClipboard.Description")); //$NON-NLS-1$
        }

        @Override
        public void actionPerformed(ActionEvent arg0) {
            String id;
            while ((id = JOptionPane.showInputDialog(frame,
                    "Please enter an ID for the pasted part.")) != null) {
                id = id.trim();
                if (id.isEmpty()) {
                    break;
                }
                if (configuration.getPart(id) == null) {
                    break;
                }
                MessageBoxes.errorBox(frame, "Error", "Part ID " + id + " already exists.");
            }
            if (id == null || id.isEmpty()) {
                return;
            }
            try {
                try {
                    Configuration.get().lockListeners();
                    Serializer ser = Configuration.createSerializer();
                    Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    String s = (String) clipboard.getData(DataFlavor.stringFlavor);
                    StringReader r = new StringReader(s);
                    Part part = ser.read(Part.class, s);
                    part.setId(id);
                    Configuration.get().addPart(part);
                    tableModel.fireTableDataChanged();
                    Helpers.selectLastTableRow(table);
                } finally {
                    Configuration.get().unlockListeners();
                }
            }
            catch (Exception e) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Paste Failed", e);
            }
        }
    };
    /** Run a task on a daemon thread; show an error dialog on the EDT if it throws. */
    private PartDatabase getPartDb() {
        Machine machine = Configuration.get().getMachine();
        if (machine instanceof ReferenceMachine) {
            PartDatabase db = ((ReferenceMachine) machine).getPartDatabase();
            if (db != null && db.isConnected()) {
                return db;
            }
        }
        return null;
    }

    public final Action importFromPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbAdd);
            putValue(NAME, "Import from PartDB");
            putValue(SHORT_DESCRIPTION, "Import a part from the external part database by name.");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            PartDatabase db = getPartDb();
            if (db == null) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Error",
                        "No part database connected. Configure it in Machine Setup.");
                return;
            }
            PartDbSearchDialog dialog = new PartDbSearchDialog(frame, db);
            dialog.setVisible(true);
            String name = dialog.getSelectedName();
            if (name == null) {
                return;
            }
            UiUtils.messageBoxOnException(() -> {
                Part part = db.importPart(name);
                tableModel.fireTableDataChanged();
                Helpers.selectObjectTableRow(table, part);
            });
        }
    };

    public final Action updateFromPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbPull);
            putValue(NAME, "Update from PartDB");
            putValue(SHORT_DESCRIPTION, "Refresh the selected part's fields from the external part database.");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            PartDatabase db = getPartDb();
            if (db == null) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Error",
                        "No part database connected. Configure it in Machine Setup.");
                return;
            }
            List<Part> parts = getSelections();
            if (parts.isEmpty()) {
                return;
            }
            UiUtils.messageBoxOnException(() -> {
                for (Part part : parts) {
                    db.updatePart(part);
                }
                tableModel.fireTableDataChanged();
            });
        }
    };

    public final Action pushToPartDbAction = new AbstractAction() {
        {
            putValue(SMALL_ICON, Icons.partDbPush);
            putValue(NAME, "Push to PartDB");
            putValue(SHORT_DESCRIPTION, "Update the selected part in the external part database.");
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            PartDatabase db = getPartDb();
            if (db == null) {
                MessageBoxes.errorBox(getTopLevelAncestor(), "Error",
                        "No part database connected. Configure it in Machine Setup.");
                return;
            }
            List<Part> parts = getSelections();
            if (parts.isEmpty()) {
                return;
            }
            UiUtils.messageBoxOnException(() -> {
                for (Part part : parts) {
                    db.pushPart(part);
                }
            });
        }
    };

    private int selectedTab;
    private String priorPartId;
    private boolean rebuildingTabs = false;

    public void updateWizards() {
        List<Part> selections = getSelections();

        if (selections.size() > 1) {
            singleSelectionActionGroup.setEnabled(false);
            multiSelectionActionGroup.setEnabled(true);
        }
        else {
            multiSelectionActionGroup.setEnabled(false);
            singleSelectionActionGroup.setEnabled(!selections.isEmpty());
        }

        Part selectedPart = getSelectedPart();
        
        if (tabbedPane.getTabCount() > 0) {
            selectedTab = tabbedPane.getSelectedIndex();
        }
        rebuildingTabs = true;

        for (Component comp : tabbedPane.getComponents()) {
            if (comp instanceof AbstractConfigurationWizard) {
                ((AbstractConfigurationWizard) comp).dispose();
            }
        }
        tabbedPane.removeAll();

        if (selectedPart != null) {
            priorPartId = selectedPart.getId();
            this.selectedPart = selectedPart;
            if (partDb != null && partDb.isEnabled()) {
                tabbedPane.add("Part Database", new PartDbDetailsPanel(selectedPart, partDb));
            }
            Wizard wizard = new PartSettingsWizard(selectedPart);
            wizard.setWizardContainer(PartsPanel.this);
            tabbedPane.add(Translations.getString("PartsPanel.SettingsTab.title"), //$NON-NLS-1$
                    (JPanel) wizard);

            for (PartAlignment partAlignment : Configuration.get().getMachine().getPartAlignments()) {
                wizard = partAlignment.getPartConfigurationWizard(selectedPart);
                if (wizard != null) {
                    wizard.setWizardContainer(PartsPanel.this);
                    tabbedPane.addTab(wizard.getWizardName(), (JPanel) wizard);
                }
            }
            
            FiducialLocator fiducialLocator =
                    Configuration.get().getMachine().getFiducialLocator();
            wizard = fiducialLocator.getPartConfigurationWizard(selectedPart);
            if (wizard != null) {
                wizard.setWizardContainer(PartsPanel.this);
                tabbedPane.add(wizard.getWizardName(), (JPanel) wizard);
            }
            MainFrame mainFrame = MainFrame.get();
            if (mainFrame.getTabs().getSelectedComponent() == mainFrame.getPartsTab()
                    && Configuration.get().getTablesLinked() == TablesLinked.Linked) {
                mainFrame.getPackagesTab().selectPackageInTable(selectedPart.getPackage());
                mainFrame.getFeedersTab().selectFeederForPart(selectedPart);
                mainFrame.getVisionSettingsTab().selectVisionSettingsInTable(selectedPart);
            }
            
            if (selectedTab >= 0 && selectedTab < tabbedPane.getTabCount()) {
                tabbedPane.setSelectedIndex(selectedTab);
            }
        }
        rebuildingTabs = false;

        // If the Part Database tab is already selected after rebuild, trigger load now.
        Component selected = tabbedPane.getSelectedComponent();
        if (selected instanceof PartDbDetailsPanel) {
            ((PartDbDetailsPanel) selected).loadIfNeeded();
        }


        revalidate();
        repaint();
    }

    public void selectPartInTableAndUpdateLinks(Part part) {
        selectPartInTable(part);

        if(Configuration.get().getTablesLinked() == TablesLinked.Linked)
        {
            MainFrame mainFrame = MainFrame.get();
            mainFrame.getPartsTab().selectPartInTable(part);
            if (part != null) {
                mainFrame.getPackagesTab().selectPackageInTable(part.getPackage());
            }
            mainFrame.getFeedersTab().selectFeederForPart(part);
            mainFrame.getVisionSettingsTab().selectVisionSettingsInTable(part);
        }
    }

    public void selectPartInTable(Part part) {
        if (getSelectedPart() != part) {
            Helpers.selectObjectTableRow(table, part);
        }
    }

    @Override
    public void wizardCompleted(Wizard wizard) {}

    @Override
    public void wizardCancelled(Wizard wizard) {}
}
