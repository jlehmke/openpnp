package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JOptionPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import org.openpnp.gui.support.Icons;
import org.openpnp.machine.reference.PartDbDatabase;
import org.openpnp.machine.reference.PartDbDatabase.PartDbLot;
import org.openpnp.machine.reference.PartDbDatabase.PartDbRawData;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Part;
import org.pmw.tinylog.Logger;

@SuppressWarnings("serial")
public class PartDbDetailsPanel extends JPanel {

    private static final int ROW_HEIGHT      = 0;
    private static final int ROW_BODY_WIDTH  = 1;
    private static final int ROW_BODY_LENGTH = 2;
    private static final int ROW_COUNT       = 3;

    private static final String[] ROW_NAMES = {
        "Height (mm)", "Body Width (mm)", "Body Length (mm)"
    };

    private static final int IMG_SIZE = 80;

    private final Part part;
    private final PartDbDatabase db;
    private final DetailsTableModel tableModel;
    private final LotsTableModel lotsTableModel;
    private final JTable lotsTable;
    private final JLabel statusLabel;
    private final JButton datasheetBtn;
    private final JButton viewInDbBtn;
    private final JSpinner qtySpinner;
    private final JButton addStockBtn;
    private final JButton removeStockBtn;
    private final JLabel imgLabel;
    private final JPanel imgWrapper;
    private final JTextField nameLabel;
    private final JTextField mpnLabel;
    private final JTextField ipnLabel;
    private final JTextField pkgLabel;
    private final JTextArea descLabel;
    private final JTextField kicadFootprintField;
    private final JButton applyPadsBtn;
    private PartDbRawData rawData;
    private BufferedImage imgOriginal;
    private boolean loaded;

    public PartDbDetailsPanel(Part part, PartDbDatabase db) {
        this.part = part;
        this.db = db;

        setLayout(new BorderLayout(0, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // --- Header: image + info ---
        imgLabel = new JLabel();
        imgLabel.setHorizontalAlignment(JLabel.CENTER);
        imgLabel.setVerticalAlignment(JLabel.CENTER);
        // Square wrapper: preferred width tracks actual height so the box is always square.
        // Placed in BorderLayout.WEST so it fills the full header height automatically.
        imgWrapper = new JPanel(new BorderLayout()) {
            @Override public Dimension getPreferredSize() {
                int h = getHeight() > 0 ? getHeight() : IMG_SIZE;
                return new Dimension(h, h);
            }
            @Override public Dimension getMinimumSize() { return new Dimension(IMG_SIZE, IMG_SIZE); }
        };
        imgWrapper.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY));
        imgWrapper.add(imgLabel, BorderLayout.CENTER);
        imgWrapper.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                // Re-trigger layout once so width catches up to height
                Container parent = imgWrapper.getParent();
                if (parent != null && imgWrapper.getWidth() != imgWrapper.getHeight()) {
                    parent.revalidate();
                }
                rescaleImage();
            }
        });

        nameLabel = new JTextField(" ");
        nameLabel.setEditable(false);
        nameLabel.setOpaque(false);
        nameLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 15f));
        nameLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, nameLabel.getPreferredSize().height));

        mpnLabel = new JTextField(" ");
        mpnLabel.setEditable(false);
        mpnLabel.setOpaque(false);
        mpnLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        mpnLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, mpnLabel.getPreferredSize().height));

        ipnLabel = new JTextField(" ");
        ipnLabel.setEditable(false);
        ipnLabel.setOpaque(false);
        ipnLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        ipnLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, ipnLabel.getPreferredSize().height));

        pkgLabel = new JTextField(" ");
        pkgLabel.setEditable(false);
        pkgLabel.setOpaque(false);
        pkgLabel.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        pkgLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, pkgLabel.getPreferredSize().height));

        descLabel = new JTextArea();
        descLabel.setEditable(false);
        descLabel.setLineWrap(true);
        descLabel.setWrapStyleWord(true);
        descLabel.setOpaque(false);
        descLabel.setFont(new JLabel().getFont());
        descLabel.setRows(2);

        JScrollPane descScroll = new JScrollPane(descLabel);
        nameLabel.setAlignmentX(LEFT_ALIGNMENT);
        mpnLabel.setAlignmentX(LEFT_ALIGNMENT);
        ipnLabel.setAlignmentX(LEFT_ALIGNMENT);
        pkgLabel.setAlignmentX(LEFT_ALIGNMENT);
        descScroll.setAlignmentX(LEFT_ALIGNMENT);

        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        infoPanel.add(nameLabel);
        infoPanel.add(Box.createVerticalStrut(2));
        infoPanel.add(mpnLabel);
        infoPanel.add(ipnLabel);
        infoPanel.add(pkgLabel);
        infoPanel.add(Box.createVerticalStrut(2));
        infoPanel.add(descScroll);

        JPanel headerPanel = new JPanel(new BorderLayout(4, 0));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        headerPanel.add(imgWrapper, BorderLayout.WEST);
        headerPanel.add(infoPanel, BorderLayout.CENTER);

        // --- Parameters table ---
        tableModel = new DetailsTableModel();
        JTable table = new JTable(tableModel);
        table.setDefaultRenderer(Object.class, new ConflictRenderer());
        table.setRowHeight(22);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        // Column 0 (Property): size to fit content; columns 1-3: equal share of remaining width.
        table.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                int col0Width = 0;
                for (int row = 0; row < tableModel.getRowCount(); row++) {
                    Component c = table.prepareRenderer(table.getCellRenderer(row, 0), row, 0);
                    col0Width = Math.max(col0Width, c.getPreferredSize().width + 8);
                }
                int total = table.getWidth();
                int dataWidth = Math.max(0, total - col0Width) / 3;
                table.getColumnModel().getColumn(0).setPreferredWidth(col0Width);
                table.getColumnModel().getColumn(1).setPreferredWidth(dataWidth);
                table.getColumnModel().getColumn(2).setPreferredWidth(dataWidth);
                table.getColumnModel().getColumn(3).setPreferredWidth(dataWidth);
            }
        });
        kicadFootprintField = new JTextField();
        kicadFootprintField.setEditable(false);
        applyPadsBtn = new JButton("Apply Pads");
        applyPadsBtn.setEnabled(false);
        applyPadsBtn.setToolTipText("Import pad geometry from the KiCad library into the current package");
        applyPadsBtn.addActionListener(e -> applyKicadPads());

        JPanel kicadRow = new JPanel(new BorderLayout(4, 0));
        kicadRow.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        kicadRow.add(new JLabel("KiCad Footprint:"), BorderLayout.WEST);
        kicadRow.add(kicadFootprintField, BorderLayout.CENTER);
        kicadRow.add(applyPadsBtn, BorderLayout.EAST);

        JPanel paramsPanel = new JPanel(new BorderLayout());
        paramsPanel.setBorder(new TitledBorder("Parameters"));
        paramsPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        paramsPanel.add(kicadRow, BorderLayout.SOUTH);

        // --- Lots table ---
        lotsTableModel = new LotsTableModel();
        lotsTable = new JTable(lotsTableModel);
        lotsTable.setRowSelectionAllowed(false);
        lotsTable.setRowHeight(22);
        lotsTable.getColumnModel().getColumn(0).setPreferredWidth(30);   // Active
        lotsTable.getColumnModel().getColumn(0).setMaxWidth(30);
        lotsTable.getColumnModel().getColumn(1).setPreferredWidth(50);   // ID
        lotsTable.getColumnModel().getColumn(1).setMaxWidth(70);
        lotsTable.getColumnModel().getColumn(2).setPreferredWidth(180);  // Description
        lotsTable.getColumnModel().getColumn(3).setPreferredWidth(150);  // Storage Location
        lotsTable.getColumnModel().getColumn(4).setPreferredWidth(70);   // Amount
        lotsTable.getColumnModel().getColumn(4).setMaxWidth(90);
        lotsTable.setPreferredScrollableViewportSize(
                new Dimension(0, 5 * lotsTable.getRowHeight()));

        qtySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));  // integers only
        ((JSpinner.DefaultEditor) qtySpinner.getEditor()).getTextField().setColumns(4);
        removeStockBtn = new JButton(Icons.delete);
        removeStockBtn.setEnabled(false);
        removeStockBtn.setToolTipText("Remove from active lot");
        removeStockBtn.addActionListener(e -> doAdjustStock(-1));
        addStockBtn = new JButton(Icons.add);
        addStockBtn.setEnabled(false);
        addStockBtn.setToolTipText("Add to active lot");
        addStockBtn.addActionListener(e -> doAdjustStock(+1));

        JPanel adjustPanel = new JPanel(new BorderLayout(4, 0));
        adjustPanel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        JPanel adjustRight = new JPanel();
        adjustRight.add(new JLabel("Change stock level:"));
        adjustRight.add(qtySpinner);
        adjustRight.add(addStockBtn);
        adjustRight.add(removeStockBtn);
        adjustPanel.add(adjustRight, BorderLayout.EAST);

        JPanel lotsPanel = new JPanel(new BorderLayout());
        lotsPanel.setBorder(new TitledBorder("Stock Lots"));
        lotsPanel.add(new JScrollPane(lotsTable), BorderLayout.CENTER);
        lotsPanel.add(adjustPanel, BorderLayout.SOUTH);

        JPanel centerPanel = new JPanel(new BorderLayout(0, 0));
        centerPanel.add(lotsPanel, BorderLayout.NORTH);
        centerPanel.add(paramsPanel, BorderLayout.CENTER);

        // --- Bottom bar ---
        statusLabel = new JLabel(" ");
        JButton refreshBtn = new JButton("Refresh");
        refreshBtn.addActionListener(e -> load());

        datasheetBtn = new JButton("Datasheet");
        datasheetBtn.setEnabled(false);
        datasheetBtn.addActionListener(e -> openDatasheet());

        viewInDbBtn = new JButton("View in DB");
        viewInDbBtn.setEnabled(false);
        viewInDbBtn.addActionListener(e -> openInDb());

        JPanel rightBtns = new JPanel();  // default FlowLayout
        rightBtns.add(datasheetBtn);
        rightBtns.add(viewInDbBtn);
        rightBtns.add(refreshBtn);

        JPanel bottomBar = new JPanel(new BorderLayout(4, 0));
        bottomBar.add(statusLabel, BorderLayout.CENTER);
        bottomBar.add(rightBtns, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);
        add(centerPanel, BorderLayout.CENTER);
        add(bottomBar, BorderLayout.SOUTH);
    }

    public void loadIfNeeded() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    private void load() {
        if (!db.isConnected()) {
            statusLabel.setText("Not connected to database.");
            return;
        }
        statusLabel.setText("Loading\u2026");
        rawData = null;
        datasheetBtn.setEnabled(false);
        viewInDbBtn.setEnabled(false);
        addStockBtn.setEnabled(false);
        removeStockBtn.setEnabled(false);
        applyPadsBtn.setEnabled(false);
        kicadFootprintField.setText("");
        tableModel.fireTableDataChanged();
        lotsTableModel.fireTableDataChanged();
        clearHeader();

        new SwingWorker<PartDbRawData, Void>() {
            private byte[] imgBytes;

            @Override
            protected PartDbRawData doInBackground() throws Exception {
                PartDbRawData d = db.fetchRawData(part.getId());
                if (d.thumbnailUrl != null) {
                    try {
                        imgBytes = db.requestBytes(d.thumbnailUrl);
                    } catch (Exception e) {
                        Logger.debug("PartDB: could not fetch thumbnail: {}", e.getMessage());
                    }
                }
                return d;
            }

            @Override
            protected void done() {
                try {
                    rawData = get();
                    populateHeader(rawData, imgBytes);
                    tableModel.fireTableDataChanged();
                    lotsTableModel.fireTableDataChanged();
                    preselectLot();
                    datasheetBtn.setEnabled(rawData.datasheetUrl != null);
                    viewInDbBtn.setEnabled(true);
                    boolean hasLots = !rawData.lots.isEmpty();
                    addStockBtn.setEnabled(hasLots);
                    removeStockBtn.setEnabled(hasLots);
                    String kicad = rawData.kicadFromPart != null ? rawData.kicadFromPart
                            : rawData.kicadFromFp;
                    kicadFootprintField.setText(kicad != null ? kicad : "");
                    applyPadsBtn.setEnabled(kicad != null && !kicad.isEmpty()
                            && part.getPackage() != null);
                    statusLabel.setText("Loaded from database.");
                } catch (InterruptedException | ExecutionException e) {
                    datasheetBtn.setEnabled(false);
                    viewInDbBtn.setEnabled(false);
                    statusLabel.setText("Not found in database.");
                    Logger.debug("PartDB details: {}", e.getMessage());
                }
            }
        }.execute();
    }

    private void doAdjustStock(int sign) {
        if (rawData == null || rawData.lots.isEmpty()) {
            return;
        }
        int lotId = db.getSelectedLotId(part.getId());
        if (lotId < 0) {
            return;
        }
        int delta = sign * (Integer) qtySpinner.getValue();
        addStockBtn.setEnabled(false);
        removeStockBtn.setEnabled(false);
        statusLabel.setText("Updating stock\u2026");

        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                return db.adjustLotAmount(lotId, delta);
            }
            @Override
            protected void done() {
                try {
                    int newAmount = get();
                    for (int i = 0; i < rawData.lots.size(); i++) {
                        if (rawData.lots.get(i).id == lotId) {
                            PartDbLot old = rawData.lots.get(i);
                            rawData.lots.set(i, new PartDbLot(old.id, old.description,
                                    old.storageLocation, newAmount));
                            break;
                        }
                    }
                    lotsTableModel.fireTableDataChanged();
                    statusLabel.setText("Stock updated.");
                } catch (Exception e) {
                    statusLabel.setText("Update failed: " + e.getCause().getMessage());
                    Logger.warn("PartDB: stock update failed: {}", e.getMessage());
                } finally {
                    addStockBtn.setEnabled(true);
                    removeStockBtn.setEnabled(true);
                }
            }
        }.execute();
    }

    private void preselectLot() {
        if (rawData == null || rawData.lots.isEmpty()) {
            return;
        }
        int stored = db.getSelectedLotId(part.getId());
        boolean found = rawData.lots.stream().anyMatch(l -> l.id == stored);
        if (!found) {
            // Auto-select first lot
            db.setSelectedLotId(part.getId(), rawData.lots.get(0).id);
            lotsTableModel.fireTableDataChanged();
        }
    }

    private void openDatasheet() {
        if (rawData == null || rawData.datasheetUrl == null) {
            return;
        }
        try {
            String dsUrl = rawData.datasheetUrl;
            URI uri;
            if (dsUrl.startsWith("http://") || dsUrl.startsWith("https://")) {
                uri = URI.create(dsUrl);
            } else {
                // Internal path — resolve against base URL
                String base = db.getUrl().replaceAll("/+$", "");
                uri = URI.create(base + dsUrl);
            }
            Desktop.getDesktop().browse(uri);
        } catch (Exception e) {
            Logger.warn("PartDB: could not open datasheet: {}", e.getMessage());
        }
    }

    private void openInDb() {
        if (rawData == null) {
            return;
        }
        try {
            String base = db.getUrl().replaceAll("/+$", "");
            Desktop.getDesktop().browse(URI.create(base + "/en/part/" + rawData.partDbId + "/info"));
        } catch (Exception e) {
            Logger.warn("PartDB: could not open part page: {}", e.getMessage());
        }
    }

    private void applyKicadPads() {
        if (rawData == null || part.getPackage() == null) {
            return;
        }
        String kicad = rawData.kicadFromPart != null ? rawData.kicadFromPart : rawData.kicadFromFp;
        if (kicad == null || kicad.isEmpty()) {
            return;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                "<html>Applying KiCad pads will overwrite the current footprint pads for package <b>"
                        + part.getPackage().getId() + "</b>.<br>"
                        + "It is not recommended to overwrite a footprint that was set manually.<br><br>"
                        + "Continue?</html>",
                "Apply KiCad Pads",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }
        boolean ok = db.importKicadPads(part.getPackage(), kicad);
        if (ok) {
            statusLabel.setText("KiCad pads applied.");
        } else if (db.getKicadLibraryPath() == null || db.getKicadLibraryPath().isEmpty()) {
            statusLabel.setText("KiCad library path not configured (see Machine \u2192 Part Database settings).");
        } else {
            statusLabel.setText("Footprint '" + kicad + "' not found in configured library path.");
        }
    }

    private void rescaleImage() {
        if (imgOriginal == null) {
            imgLabel.setIcon(null);
            return;
        }
        int s = imgWrapper.getHeight() > 0 ? imgWrapper.getHeight() : IMG_SIZE;
        Image scaled = imgOriginal.getScaledInstance(s, s, Image.SCALE_SMOOTH);
        imgLabel.setIcon(new ImageIcon(scaled));
    }

    private void clearHeader() {
        imgOriginal = null;
        imgLabel.setIcon(null);
        nameLabel.setText(" ");
        mpnLabel.setText(" ");
        ipnLabel.setText(" ");
        pkgLabel.setText(" ");
        descLabel.setText("");
    }

    private void populateHeader(PartDbRawData d, byte[] imgBytes) {
        nameLabel.setText(d.partName != null ? d.partName : " ");
        mpnLabel.setText(d.mpn != null ? "MPN: " + d.mpn : " ");
        ipnLabel.setText(d.ipn != null ? "IPN: " + d.ipn : " ");
        pkgLabel.setText(d.packageName != null ? "Package: " + d.packageName : " ");
        descLabel.setText(d.description != null ? d.description : "");

        imgOriginal = null;
        if (imgBytes != null) {
            try {
                imgOriginal = ImageIO.read(new ByteArrayInputStream(imgBytes));
            } catch (Exception e) {
                Logger.debug("PartDB: could not decode thumbnail: {}", e.getMessage());
            }
        }
        rescaleImage();
    }

    // -------------------------------------------------------------------------
    // Value helpers
    // -------------------------------------------------------------------------

    private String getLocalValue(int row) {
        switch (row) {
            case ROW_HEIGHT:
                if (part.getHeight() == null) {
                    return "";
                }
                Length h = part.getHeight().convertToUnits(LengthUnit.Millimeters);
                return h.getValue() > 0 ? fmt(h.getValue()) : "";
            case ROW_BODY_WIDTH:
                if (part.getPackage() == null) {
                    return "";
                }
                double bw = part.getPackage().getFootprint().getBodyWidth();
                return bw > 0 ? fmt(bw) : "";
            case ROW_BODY_LENGTH:
                if (part.getPackage() == null) {
                    return "";
                }
                double bl = part.getPackage().getFootprint().getBodyHeight();
                return bl > 0 ? fmt(bl) : "";
            default:
                return "";
        }
    }

    private String getPartValue(int row) {
        if (rawData == null) {
            return "";
        }
        switch (row) {
            case ROW_HEIGHT:      return fmm(rawData.partHeight);
            case ROW_BODY_WIDTH:  return fmm(rawData.partBodyWidth);
            case ROW_BODY_LENGTH: return fmm(rawData.partBodyLength);
            default:              return "";
        }
    }

    private String getFpValue(int row) {
        if (rawData == null) {
            return "";
        }
        switch (row) {
            case ROW_HEIGHT:      return fmm(rawData.fpHeight);
            case ROW_BODY_WIDTH:  return fmm(rawData.fpBodyWidth);
            case ROW_BODY_LENGTH: return fmm(rawData.fpBodyLength);
            default:              return "";
        }
    }

    private String getResolvedValue(int row) {
        String pv = getPartValue(row);
        return (!pv.isEmpty()) ? pv : getFpValue(row);
    }

    private static String str(String s) {
        return (s != null && !s.isEmpty()) ? s : "";
    }

    private static String fmm(Double v) {
        return (v != null && v > 0) ? fmt(v) : "";
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    // -------------------------------------------------------------------------
    // Lots table model
    // -------------------------------------------------------------------------

    private class LotsTableModel extends AbstractTableModel {
        @Override public int getRowCount()    { return rawData == null ? 0 : rawData.lots.size(); }
        @Override public int getColumnCount() { return 5; }
        @Override public String getColumnName(int col) {
            switch (col) {
                case 0: return "";             // Active (checkbox)
                case 1: return "ID";
                case 2: return "Description";
                case 3: return "Storage Location";
                case 4: return "Amount";
                default: return "";
            }
        }
        @Override public Class<?> getColumnClass(int col) {
            switch (col) {
                case 0: return Boolean.class;
                case 1: return Integer.class;
                case 4: return Integer.class;
                default: return String.class;
            }
        }
        @Override public boolean isCellEditable(int row, int col) { return col == 0; }
        @Override public Object getValueAt(int row, int col) {
            PartDbLot lot = rawData.lots.get(row);
            switch (col) {
                case 0: return lot.id == db.getSelectedLotId(part.getId());
                case 1: return lot.id;
                case 2: return lot.description;
                case 3: return lot.storageLocation;
                case 4: return lot.amount;
                default: return "";
            }
        }
        @Override public void setValueAt(Object value, int row, int col) {
            if (col == 0 && Boolean.TRUE.equals(value) && rawData != null) {
                db.setSelectedLotId(part.getId(), rawData.lots.get(row).id);
                fireTableDataChanged();  // Refresh all checkboxes (only one active)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Table model
    // -------------------------------------------------------------------------

    private class DetailsTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return ROW_COUNT;
        }
        @Override
        public int getColumnCount() {
            return 4;
        }
        @Override
        public String getColumnName(int col) {
            switch (col) {
                case 0: return "Property";
                case 1: return "Local";
                case 2: return "Remote \u2013 Part";
                case 3: return "Remote \u2013 Footprint";
                default: return "";
            }
        }
        @Override
        public Object getValueAt(int row, int col) {
            switch (col) {
                case 0: return ROW_NAMES[row];
                case 1: return getLocalValue(row);
                case 2: return getPartValue(row);
                case 3: return getFpValue(row);
                default: return "";
            }
        }
        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 1 && row <= ROW_BODY_LENGTH;
        }
        @Override
        public void setValueAt(Object aValue, int row, int col) {
            if (col != 1) {
                return;
            }
            String s = aValue.toString().trim();
            try {
                switch (row) {
                    case ROW_HEIGHT:
                        part.setHeight(new Length(Double.parseDouble(s), LengthUnit.Millimeters));
                        break;
                    case ROW_BODY_WIDTH:
                        if (part.getPackage() != null) {
                            part.getPackage().getFootprint().setBodyWidth(Double.parseDouble(s));
                        }
                        break;
                    case ROW_BODY_LENGTH:
                        if (part.getPackage() != null) {
                            part.getPackage().getFootprint().setBodyHeight(Double.parseDouble(s));
                        }
                        break;
                    default:
                        break;
                }
            } catch (NumberFormatException e) {
                // ignore invalid input
            }
            fireTableCellUpdated(row, col);
        }
    }

    // -------------------------------------------------------------------------
    // Cell renderer
    // -------------------------------------------------------------------------

    private class ConflictRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;
        private final Color amber  = new Color(255, 215, 100);
        private final Color differ = new Color(255, 185, 185);

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            setHorizontalAlignment(col == 0 ? SwingConstants.LEFT : SwingConstants.CENTER);
            if (!isSelected) {
                setBackground(table.getBackground());
                if (rawData != null) {
                    String local    = getLocalValue(row);
                    String resolved = getResolvedValue(row);
                    String partVal  = getPartValue(row);
                    String fpVal    = getFpValue(row);

                    if (col == 1 && !local.isEmpty() && !resolved.isEmpty()
                            && !local.equals(resolved)) {
                        setBackground(amber);
                    }
                    if ((col == 2 || col == 3) && !partVal.isEmpty() && !fpVal.isEmpty()
                            && !partVal.equals(fpVal)) {
                        setBackground(differ);
                    }
                }
            }
            return this;
        }
    }
}
