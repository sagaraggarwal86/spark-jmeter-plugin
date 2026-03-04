package com.Sagar.jmeter.sampler;

import com.Sagar.jmeter.data.AggregateResult;
import com.Sagar.jmeter.parser.JTLParser;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;

public class SamplePluginSamplerUI extends AbstractSamplerGui {

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
        public boolean isCellEditable(int row, int col) {
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

    public SamplePluginSamplerUI() {
        super();
        initComponents();
        setupListeners();
    }

    private void setupListeners() {
        // Update table column header and data when percentile value changes
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

        // Setup listeners for offset and filter fields to reload data when changed
        javax.swing.event.DocumentListener filterListener = new javax.swing.event.DocumentListener() {
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
        startOffsetField.getDocument().addDocumentListener(filterListener);
        endOffsetField.getDocument().addDocumentListener(filterListener);
        includeLabelsField.getDocument().addDocumentListener(filterListener);
        excludeLabelsField.getDocument().addDocumentListener(filterListener);

        // Setup listener for regex checkbox
        regExpBox.addActionListener(e -> reloadWithCurrentFilters());
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

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));
        setBorder(makeBorder());

        // Stack title + file panel + filter panel vertically at the top
        JPanel topWrapper = new JPanel(new BorderLayout(0, 0));
        JPanel titleAndFile = new JPanel(new BorderLayout(0, 0));
        titleAndFile.add(makeTitlePanel(), BorderLayout.NORTH);
        titleAndFile.add(buildFilePanel(), BorderLayout.CENTER);
        topWrapper.add(titleAndFile, BorderLayout.NORTH);
        topWrapper.add(buildFilterPanel(), BorderLayout.CENTER);

        add(topWrapper, BorderLayout.NORTH);
        add(buildTablePanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    // ── File / Read panel ────────────────────────────────────────────────────
    private JPanel buildFilePanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder fileBorder = new TitledBorder("Write results to file / Read from file");
        fileBorder.setTitleFont(FONT_HEADER);
        panel.setBorder(fileBorder);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        // "Filename" label
        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        JLabel fileLabel = new JLabel("Filename");
        fileLabel.setFont(FONT_REGULAR);
        panel.add(fileLabel, c);

        // filename text field
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        fileNameField.setFont(FONT_REGULAR);
        panel.add(fileNameField, c);

        // Browse button
        JButton browseBtn = new JButton("Browse...");
        browseBtn.setFont(FONT_REGULAR);
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                fileNameField.setText(f.getAbsolutePath());
            }
        });
        c.gridx = 2;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(browseBtn, c);

        return panel;
    }

    // ── Filter settings panel ────────────────────────────────────────────────
    private JPanel buildFilterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder filterBorder = new TitledBorder("Filter settings");
        filterBorder.setTitleFont(FONT_HEADER);
        panel.setBorder(filterBorder);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;

        // Header row
        c.gridy = 0;
        c.gridx = 0;
        c.weightx = 0.15;
        JLabel startLabel = new JLabel("Start offset (sec)");
        startLabel.setFont(FONT_REGULAR);
        panel.add(startLabel, c);
        c.gridx = 1;
        c.weightx = 0.15;
        JLabel endLabel = new JLabel("End offset (sec)");
        endLabel.setFont(FONT_REGULAR);
        panel.add(endLabel, c);
        c.gridx = 2;
        c.weightx = 0.25;
        JLabel includeLabel = new JLabel("Include labels");
        includeLabel.setFont(FONT_REGULAR);
        panel.add(includeLabel, c);
        c.gridx = 3;
        c.weightx = 0.25;
        JLabel excludeLabel = new JLabel("Exclude labels");
        excludeLabel.setFont(FONT_REGULAR);
        panel.add(excludeLabel, c);
        c.gridx = 4;
        c.weightx = 0.05;
        JLabel regExpLabel = new JLabel("RegExp");
        regExpLabel.setFont(FONT_REGULAR);
        panel.add(regExpLabel, c);
        c.gridx = 5;
        c.weightx = 0.15;
        JLabel percentileLabel = new JLabel("Percentile (%)");
        percentileLabel.setFont(FONT_REGULAR);
        panel.add(percentileLabel, c);

        // Input row
        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        startOffsetField.setFont(FONT_REGULAR);
        panel.add(startOffsetField, c);
        c.gridx = 1;
        endOffsetField.setFont(FONT_REGULAR);
        panel.add(endOffsetField, c);
        c.gridx = 2;
        includeLabelsField.setFont(FONT_REGULAR);
        panel.add(includeLabelsField, c);
        c.gridx = 3;
        excludeLabelsField.setFont(FONT_REGULAR);
        panel.add(excludeLabelsField, c);
        c.gridx = 4;
        c.fill = GridBagConstraints.NONE;
        regExpBox.setFont(FONT_REGULAR);
        panel.add(regExpBox, c);
        c.gridx = 5;
        c.fill = GridBagConstraints.HORIZONTAL;
        percentileField.setFont(FONT_REGULAR);
        panel.add(percentileField, c);

        return panel;
    }

    // ── Results table ────────────────────────────────────────────────────────
    private JScrollPane buildTablePanel() {
        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20); // Adjust row height for better readability
        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.setPreferredSize(new Dimension(900, 300));
        return scrollPane;
    }

    // ── Bottom controls ──────────────────────────────────────────────────────
    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton saveTableDataBtn = new JButton("Save Table Data");
        saveTableDataBtn.setFont(FONT_REGULAR);
        saveTableDataBtn.addActionListener(e -> saveTableData());
        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);
        bottom.add(saveTableDataBtn);
        bottom.add(saveTableHeaderBox);
        return bottom;
    }

    // ── AbstractSamplerGui contract ──────────────────────────────────────────
    @Override
    public String getLabelResource() {
        return "sample_plugin_sampler";
    }

    @Override
    public String getStaticLabel() {
        return "Advanced Aggregate Report";
    }

    @Override
    public TestElement createTestElement() {
        SamplePluginSampler s = new SamplePluginSampler();
        modifyTestElement(s);
        return s;
    }

    @Override
    public void modifyTestElement(TestElement el) {
        configureTestElement(el);
        if (el instanceof SamplePluginSampler s) {
            s.setFileName(fileNameField.getText().trim());
            s.setFilterSettings(buildFilterString());
            s.setStart(startOffsetField.getText().trim());
            s.setDuration(endOffsetField.getText().trim());
        }
    }

    @Override
    public void configure(TestElement el) {
        super.configure(el);
        if (el instanceof SamplePluginSampler s) {
            fileNameField.setText(s.getFileName());
            startOffsetField.setText(s.getStart());
            endOffsetField.setText(s.getDuration());
        }
    }

    @Override
    public void clearGui() {
        super.clearGui();
        fileNameField.setText("");
        startOffsetField.setText("");
        endOffsetField.setText("");
        includeLabelsField.setText("");
        excludeLabelsField.setText("");
        percentileField.setText("90");
        regExpBox.setSelected(false);
        tableModel.setRowCount(0);
        cachedResults.clear();
    }

    private String buildFilterString() {
        return "start=" + startOffsetField.getText().trim()
                + ";end=" + endOffsetField.getText().trim()
                + ";include=" + includeLabelsField.getText().trim()
                + ";exclude=" + excludeLabelsField.getText().trim()
                + ";regExp=" + regExpBox.isSelected()
                + ";percentile=" + percentileField.getText().trim();
    }

    /**
     * Public method to cache loaded results and populate table.
     * Called when a JTL file has been parsed and loaded.
     */
    public void setAndDisplayResults(Map<String, AggregateResult> results) {
        this.cachedResults = new HashMap<>(results);
        try {
            int percentile = Integer.parseInt(percentileField.getText().trim());
            populateTableWithResults(results, percentile);
        } catch (NumberFormatException e) {
            populateTableWithResults(results, 90);
        }
    }

    /**
     * Load JTL file with all filter options (start offset, end offset, include/exclude labels, percentile)
     *
     * @param filePath Path to the JTL file
     * @return true if successful, false otherwise
     */
    public boolean loadJTLFile(String filePath) {
        // Store the file path for reloading with different filters
        lastLoadedFilePath = filePath;

        try {
            // Build filter options from UI fields
            JTLParser.FilterOptions options = buildFilterOptions();

            // Parse the JTL file
            JTLParser parser = new JTLParser();
            Map<String, AggregateResult> results = parser.parse(filePath, options);

            // Cache and display results
            this.cachedResults = new HashMap<>(results);
            populateTableWithResults(results, options.percentile);

            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Reload the last loaded file with current filter settings
     */
    private void reloadWithCurrentFilters() {
        if (lastLoadedFilePath == null || lastLoadedFilePath.isEmpty()) {
            return; // No file loaded yet
        }

        try {
            // Build filter options from current UI fields
            JTLParser.FilterOptions options = buildFilterOptions();

            // Parse the JTL file
            JTLParser parser = new JTLParser();
            Map<String, AggregateResult> results = parser.parse(lastLoadedFilePath, options);

            // Cache and display results
            this.cachedResults = new HashMap<>(results);
            populateTableWithResults(results, options.percentile);

        } catch (Exception e) {
            // Silently fail - user is typing and may have invalid input
            System.err.println("Error reloading with filters: " + e.getMessage());
        }
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
            JOptionPane.showMessageDialog(this,
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

        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile();

            // Ensure .csv extension
            if (!fileToSave.getName().toLowerCase().endsWith(".csv")) {
                fileToSave = new File(fileToSave.getAbsolutePath() + ".csv");
            }

            try {
                saveTableToCSV(fileToSave);
                JOptionPane.showMessageDialog(this,
                        "Table data saved successfully to:\n" + fileToSave.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this,
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
