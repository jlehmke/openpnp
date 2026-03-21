package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

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
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import org.openpnp.spi.PartDatabase;

public class PartDbSearchDialog extends JDialog {

    private final PartDatabase db;
    private final JTextField searchField = new JTextField(30);
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JList<String> resultList = new JList<>(listModel);
    private final JButton importButton = new JButton("Import");
    private final JLabel statusLabel = new JLabel(" ");

    private String selectedName = null;
    private Timer debounceTimer;
    private SwingWorker<List<String>, Void> currentWorker;

    public PartDbSearchDialog(Frame parent, PartDatabase db) {
        super(parent, "Import Part from PartDB", true);
        this.db = db;

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                importButton.setEnabled(resultList.getSelectedValue() != null);
            }
        });
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && resultList.getSelectedValue() != null) {
                    doImport();
                }
            }
        });

        importButton.setEnabled(false);
        importButton.addActionListener(e -> doImport());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        // ESC closes the dialog
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getRootPane().getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });

        // Debounce: 300 ms after last keystroke, fire a search
        debounceTimer = new Timer(300, e -> triggerSearch());
        debounceTimer.setRepeats(false);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onSearchTextChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onSearchTextChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onSearchTextChanged(); }
        });

        JPanel searchPanel = new JPanel(new BorderLayout(4, 0));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        searchPanel.add(new JLabel("Search:"), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(searchPanel, BorderLayout.NORTH);
        northPanel.add(statusPanel, BorderLayout.SOUTH);

        JScrollPane scrollPane = new JScrollPane(resultList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(importButton);
        buttonPanel.add(cancelButton);

        setLayout(new BorderLayout(0, 4));
        add(northPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);

        setSize(420, 360);
        setLocationRelativeTo(parent);
        searchField.requestFocusInWindow();
    }

    private void onSearchTextChanged() {
        debounceTimer.restart();
    }

    private void triggerSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            if (currentWorker != null) {
                currentWorker.cancel(true);
            }
            listModel.clear();
            statusLabel.setText(" ");
            importButton.setEnabled(false);
            return;
        }

        if (currentWorker != null) {
            currentWorker.cancel(true);
        }

        statusLabel.setText("Searching…");
        importButton.setEnabled(false);

        currentWorker = new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return db.searchParts(query);
            }

            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    List<String> results = get();
                    listModel.clear();
                    for (String name : results) {
                        listModel.addElement(name);
                    }
                    statusLabel.setText(results.isEmpty() ? "No results." : results.size() + " result(s)");
                } catch (Exception e) {
                    statusLabel.setText("Search failed: " + e.getMessage());
                }
            }
        };
        currentWorker.execute();
    }

    private void doImport() {
        selectedName = resultList.getSelectedValue();
        dispose();
    }

    /** Returns the selected part name, or null if the dialog was cancelled. */
    public String getSelectedName() {
        return selectedName;
    }
}
