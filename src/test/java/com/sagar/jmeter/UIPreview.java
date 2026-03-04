package com.sagar.jmeter;

import com.Sagar.jmeter.data.AggregateResult;
import com.Sagar.jmeter.parser.JTLParser;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone preview of the Advanced Aggregate Report UI.
 * Run main() directly — no JMeter runtime needed.
 */
public class UIPreview {

    // Fonts
    private static final Font FONT_HEADER = new Font("Calibri", Font.PLAIN, 13);
    private static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 11);

    // File section
    private final JTextField fileNameField = new JTextField("", 40);

    // Filter settings
    private final JTextField startOffsetField = new JTextField("", 10);
    private final JTextField endOffsetField = new JTextField("", 10);
    private final JTextField includeLabelsField = new JTextField("", 20);
    private final JTextField excludeLabelsField = new JTextField("", 20);
    private final JCheckBox regExpBox = new JCheckBox();
    private final JTextField percentileField = new JTextField("90", 10);

    // Results table
    private final String[] COLUMN_NAMES = {
            "Transaction Name", "Transaction Count", "Average", "Min", "Max",
            "90% Line", "Std. Dev.", "Error %", "Throughput"
    };
    private final DefaultTableModel tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable resultsTable = new JTable(tableModel);

    // Bottom controls
    private final JCheckBox saveTableHeaderBox = new JCheckBox("Save Table Header");

    // Cache for loaded results to support dynamic percentile updates
    private Map<String, AggregateResult> cachedResults = new HashMap<>();

    // Track the last loaded file path for reloading with new filters
    private String lastLoadedFilePath = null;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            JFrame frame = new JFrame("Advanced Aggregate Report");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new UIPreview().buildUI());
            frame.pack();
            frame.setMinimumSize(new Dimension(960, 500));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            System.out.println("[UI PREVIEW] Window opened successfully.");
        });
    }

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Setup listener for percentile field to update table column header
        percentileField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updatePercentileColumn();
                refreshTableData();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updatePercentileColumn();
                refreshTableData();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updatePercentileColumn();
                refreshTableData();
            }
        });

        // Setup listeners for offset fields to reload data when changed
        javax.swing.event.DocumentListener offsetListener = new javax.swing.event.DocumentListener() {
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                reloadWithCurrentFilters();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                reloadWithCurrentFilters();
            }

            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                reloadWithCurrentFilters();
            }
        };
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);
        includeLabelsField.getDocument().addDocumentListener(offsetListener);
        excludeLabelsField.getDocument().addDocumentListener(offsetListener);

        // Setup listener for regex checkbox
        regExpBox.addActionListener(e -> reloadWithCurrentFilters());

        // ── Title bar ──────────────────────────────────────────────────────
        JPanel titleBar = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Advanced Aggregate Report");
        title.setFont(FONT_HEADER.deriveFont(Font.BOLD));
        titleBar.add(title, BorderLayout.WEST);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel nameLabel = new JLabel("Name:");
        nameLabel.setFont(FONT_REGULAR);
        nameRow.add(nameLabel);
        JTextField nameField = new JTextField("Advanced Aggregate Report", 28);
        nameField.setFont(FONT_REGULAR);
        nameRow.add(nameField);
        JLabel commentsLabel = new JLabel("Comments:");
        commentsLabel.setFont(FONT_REGULAR);
        nameRow.add(commentsLabel);
        JTextField commentsField = new JTextField("", 28);
        commentsField.setFont(FONT_REGULAR);
        nameRow.add(commentsField);
        titleBar.add(nameRow, BorderLayout.SOUTH);

        // ── File panel ─────────────────────────────────────────────────────
        JPanel filePanel = new JPanel(new GridBagLayout());
        TitledBorder fileBorder = new TitledBorder("Write results to file / Read from file");
        fileBorder.setTitleFont(FONT_HEADER);
        filePanel.setBorder(fileBorder);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        JLabel fileLabel = new JLabel("Filename");
        fileLabel.setFont(FONT_REGULAR);
        filePanel.add(fileLabel, c);

        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        fileNameField.setFont(FONT_REGULAR);
        filePanel.add(fileNameField, c);

        JButton browseBtn = new JButton("Browse...");
        browseBtn.setFont(FONT_REGULAR);
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileFilter() {
                public boolean accept(File f) {
                    return f.isDirectory() || f.getName().toLowerCase().endsWith(".jtl");
                }

                public String getDescription() {
                    return "JTL Files (*.jtl)";
                }
            });
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                fileNameField.setText(f.getAbsolutePath());
                loadJTLFile(f.getAbsolutePath());
            }
        });
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        filePanel.add(browseBtn, c);

        // ── Filter settings panel ──────────────────────────────────────────
        JPanel filterPanel = new JPanel(new GridBagLayout());
        TitledBorder filterBorder = new TitledBorder("Filter settings");
        filterBorder.setTitleFont(FONT_HEADER);
        filterPanel.setBorder(filterBorder);
        GridBagConstraints fc2 = new GridBagConstraints();
        fc2.insets = new Insets(4, 6, 4, 6);
        fc2.anchor = GridBagConstraints.WEST;

        // Header labels
        fc2.gridy = 0;
        fc2.gridx = 0;
        fc2.weightx = 0.15;
        JLabel startLabel = new JLabel("Start offset (sec)");
        startLabel.setFont(FONT_REGULAR);
        filterPanel.add(startLabel, fc2);
        fc2.gridx = 1;
        fc2.weightx = 0.15;
        JLabel endLabel = new JLabel("End offset (sec)");
        endLabel.setFont(FONT_REGULAR);
        filterPanel.add(endLabel, fc2);
        fc2.gridx = 2;
        fc2.weightx = 0.25;
        JLabel includeLabel = new JLabel("Include labels");
        includeLabel.setFont(FONT_REGULAR);
        filterPanel.add(includeLabel, fc2);
        fc2.gridx = 3;
        fc2.weightx = 0.25;
        JLabel excludeLabel = new JLabel("Exclude labels");
        excludeLabel.setFont(FONT_REGULAR);
        filterPanel.add(excludeLabel, fc2);
        fc2.gridx = 4;
        fc2.weightx = 0.05;
        JLabel regExpLabel = new JLabel("RegExp");
        regExpLabel.setFont(FONT_REGULAR);
        filterPanel.add(regExpLabel, fc2);
        fc2.gridx = 5;
        fc2.weightx = 0.15;
        JLabel percentileLabel = new JLabel("Percentile (%)");
        percentileLabel.setFont(FONT_REGULAR);
        filterPanel.add(percentileLabel, fc2);

        // Input fields
        fc2.gridy = 1;
        fc2.fill = GridBagConstraints.HORIZONTAL;
        fc2.gridx = 0;
        startOffsetField.setFont(FONT_REGULAR);
        filterPanel.add(startOffsetField, fc2);
        fc2.gridx = 1;
        endOffsetField.setFont(FONT_REGULAR);
        filterPanel.add(endOffsetField, fc2);
        fc2.gridx = 2;
        includeLabelsField.setFont(FONT_REGULAR);
        filterPanel.add(includeLabelsField, fc2);
        fc2.gridx = 3;
        excludeLabelsField.setFont(FONT_REGULAR);
        filterPanel.add(excludeLabelsField, fc2);
        fc2.gridx = 4;
        fc2.fill = GridBagConstraints.NONE;
        regExpBox.setFont(FONT_REGULAR);
        filterPanel.add(regExpBox, fc2);
        fc2.gridx = 5;
        fc2.fill = GridBagConstraints.HORIZONTAL;
        percentileField.setFont(FONT_REGULAR);
        filterPanel.add(percentileField, fc2);

        // ── Top wrapper ────────────────────────────────────────────────────
        JPanel topWrapper = new JPanel(new BorderLayout(0, 0));
        JPanel titleAndFile = new JPanel(new BorderLayout(0, 0));
        titleAndFile.add(titleBar, BorderLayout.NORTH);
        titleAndFile.add(filePanel, BorderLayout.CENTER);
        topWrapper.add(titleAndFile, BorderLayout.NORTH);
        topWrapper.add(filterPanel, BorderLayout.CENTER);

        // ── Results table ──────────────────────────────────────────────────
        // Table will be populated when JTL file is loaded

        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20); // Adjust row height for better readability
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setPreferredSize(new Dimension(900, 200));

        // ── Bottom bar ─────────────────────────────────────────────────────
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton saveTableDataBtn = new JButton("Save Table Data");
        saveTableDataBtn.setFont(FONT_REGULAR);
        saveTableDataBtn.addActionListener(e -> saveTableData());
        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);
        bottom.add(saveTableDataBtn);
        bottom.add(saveTableHeaderBox);

        root.add(topWrapper, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    private void updatePercentileColumn() {
        String percentile = percentileField.getText().trim();
        if (percentile.isEmpty()) {
            percentile = "90";
        }
        String columnName = percentile + "% Line";
        resultsTable.getColumnModel().getColumn(5).setHeaderValue(columnName);
        resultsTable.getTableHeader().repaint();
    }

    private void refreshTableData() {
        if (cachedResults.isEmpty()) {
            return; // No data loaded yet
        }

        try {
            int percentile = Integer.parseInt(percentileField.getText().trim());
            populateTableWithResults(cachedResults, percentile);
        } catch (NumberFormatException e) {
            // Invalid percentile value, do nothing
        }
    }

    private void populateTableWithResults(Map<String, AggregateResult> results, int percentile) {
        // Clear existing data
        tableModel.setRowCount(0);

        // Format numbers
        DecimalFormat df0 = new DecimalFormat("#");
        DecimalFormat df1 = new DecimalFormat("#.0");
        DecimalFormat df2 = new DecimalFormat("0.00");  // Always show 2 decimal places

        // Add rows to table with dynamic percentile calculation
        for (AggregateResult result : results.values()) {
            Object[] row = new Object[]{
                    result.getLabel(),                          // Transaction Name
                    result.getCount(),                          // Transaction Count
                    df0.format(result.getAverage()),            // Average
                    result.getMin(),                            // Min
                    result.getMax(),                            // Max
                    df0.format(result.getPercentile(percentile)), // X% Line (dynamically calculated)
                    df1.format(result.getStdDev()),             // Std. Dev.
                    df2.format(result.getErrorPercentage()) + "%",  // Error % (always 2 decimals)
                    df1.format(result.getThroughput()) + "/sec" // Throughput
            };
            tableModel.addRow(row);
        }
    }

    private void loadJTLFile(String filePath) {
        // Store the file path for reloading with different filters
        lastLoadedFilePath = filePath;

        SwingUtilities.invokeLater(() -> {
            try {
                // Clear existing data
                tableModel.setRowCount(0);

                // Parse file with filter options
                JTLParser parser = new JTLParser();
                JTLParser.FilterOptions options = buildFilterOptions();

                Map<String, AggregateResult> results = parser.parse(filePath, options);

                // Cache results for dynamic percentile updates
                cachedResults = results;

                // Populate table with results
                populateTableWithResults(results, options.percentile);

                JOptionPane.showMessageDialog(null,
                        "Loaded " + results.size() + " transaction types from JTL file",
                        "Success", JOptionPane.INFORMATION_MESSAGE);

            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "Error loading JTL file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Reload the last loaded file with current filter settings
     */
    private void reloadWithCurrentFilters() {
        if (lastLoadedFilePath == null || lastLoadedFilePath.isEmpty()) {
            return; // No file loaded yet
        }

        SwingUtilities.invokeLater(() -> {
            try {
                // Clear existing data
                tableModel.setRowCount(0);

                // Parse file with current filter options
                JTLParser parser = new JTLParser();
                JTLParser.FilterOptions options = buildFilterOptions();

                Map<String, AggregateResult> results = parser.parse(lastLoadedFilePath, options);

                // Cache results for dynamic percentile updates
                cachedResults = results;

                // Populate table with results
                populateTableWithResults(results, options.percentile);

            } catch (Exception e) {
                // Silently fail - user is typing and may have invalid input
                System.err.println("Error reloading with filters: " + e.getMessage());
            }
        });
    }

    /**
     * Build filter options from current UI field values
     */
    private JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions options = new JTLParser.FilterOptions();

        options.includeLabels = includeLabelsField.getText().trim();
        options.excludeLabels = excludeLabelsField.getText().trim();
        options.regExp = regExpBox.isSelected();

        // Parse start offset (in seconds)
        try {
            String startOffsetStr = startOffsetField.getText().trim();
            if (!startOffsetStr.isEmpty()) {
                options.startOffset = Integer.parseInt(startOffsetStr);
            }
        } catch (NumberFormatException e) {
            options.startOffset = 0;
        }

        // Parse end offset (in seconds)
        try {
            String endOffsetStr = endOffsetField.getText().trim();
            if (!endOffsetStr.isEmpty()) {
                options.endOffset = Integer.parseInt(endOffsetStr);
            }
        } catch (NumberFormatException e) {
            options.endOffset = 0;
        }

        // Parse percentile
        try {
            options.percentile = Integer.parseInt(percentileField.getText().trim());
        } catch (NumberFormatException e) {
            options.percentile = 90;
        }

        return options;
    }

    /**
     * Save table data to CSV file in JMeter format
     */
    private void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(null,
                    "No data to save. Please load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save Table Data");
        fileChooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().toLowerCase().endsWith(".csv");
            }

            public String getDescription() {
                return "JMeter CSV Files (*.csv)";
            }
        });

        // Suggest default filename
        fileChooser.setSelectedFile(new File("aggregate_report.csv"));

        int userSelection = fileChooser.showSaveDialog(null);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();

            // Ensure .csv extension
            if (!fileToSave.getName().toLowerCase().endsWith(".csv")) {
                fileToSave = new File(fileToSave.getAbsolutePath() + ".csv");
            }

            try {
                saveTableToCSV(fileToSave);
                JOptionPane.showMessageDialog(null,
                        "Table data saved successfully to:\n" + fileToSave.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "Error saving file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * Write table data to CSV file - saves exactly what's shown in the UI
     */
    private void saveTableToCSV(File file) throws Exception {
        try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
            // Write header if checkbox is selected
            if (saveTableHeaderBox.isSelected()) {
                // Get column headers from table model
                StringBuilder header = new StringBuilder();
                for (int col = 0; col < tableModel.getColumnCount(); col++) {
                    if (col > 0) header.append(",");
                    header.append(tableModel.getColumnName(col));
                }
                writer.write(header.toString());
                writer.newLine();
            }

            // Write data rows - exactly as displayed in UI
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                StringBuilder line = new StringBuilder();

                for (int col = 0; col < tableModel.getColumnCount(); col++) {
                    if (col > 0) line.append(",");

                    Object value = tableModel.getValueAt(row, col);
                    String cellValue = value != null ? value.toString() : "";

                    // Escape CSV special characters for first column (label)
                    if (col == 0) {
                        cellValue = escapeCSV(cellValue);
                    }

                    line.append(cellValue);
                }

                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    /**
     * Escape CSV special characters
     */
    private String escapeCSV(String value) {
        if (value == null) return "";

        // If value contains comma, quote, or newline, wrap in quotes and escape quotes
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }

        return value;
    }
}
