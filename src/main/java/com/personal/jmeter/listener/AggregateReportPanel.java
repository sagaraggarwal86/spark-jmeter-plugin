package com.personal.jmeter.listener;

import com.personal.jmeter.ai.AiReportService;
import com.personal.jmeter.ai.HtmlReportRenderer;
import com.personal.jmeter.ai.PromptBuilder;
import com.personal.jmeter.parser.JTLParser;
import org.apache.jmeter.visualizers.SamplingStatCalculator;



import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Reusable Swing panel containing all shared UI and logic for the
 * Configurable Aggregate Report: filter settings, results table, test-time info,
 * CSV export, and AI report generation.
 *
 * <p>Used by both {@link ListenerGUI} (JMeter plugin) and
 * {@code UIPreview} (standalone dev preview). Eliminates the duplication
 * that previously existed between those two classes.</p>
 *
 * <h3>Public API for parent components</h3>
 * <ul>
 *   <li>{@link #loadJtlFile(String)} / {@link #loadJtlFile(String, boolean)}</li>
 *   <li>{@link #clearAll()}</li>
 *   <li>{@link #setSuppressReload(boolean)} — suppress auto-reload during bulk field updates</li>
 *   <li>{@link #setMetadataSupplier(Supplier)} — supply scenario metadata for the AI report</li>
 *   <li>Getters/setters for {@code startOffset}, {@code endOffset}, {@code percentile}</li>
 * </ul>
 */
public class AggregateReportPanel extends JPanel {



    // ── Column definitions ───────────────────────────────────────
    static final String[] ALL_COLUMNS = {
            "Transaction Name", "Transaction Count", "Transaction Passed",
            "Transaction Failed", "Avg Response Time(ms)", "Min Response Time(ms)",
            "Max Response Time(ms)", "90th Percentile(ms)", "Std. Dev.", "Error Rate", "TPS"
    };
    static final int    PERCENTILE_COL_INDEX = 7;
    static final String TOTAL_LABEL          = "TOTAL";

    // ── Fonts ────────────────────────────────────────────────────
    public static final Font FONT_HEADER  = new Font("Calibri", Font.PLAIN, 13);
    public static final Font FONT_REGULAR = new Font("Calibri", Font.PLAIN, 11);

    /** Thread-safe; safe to use as a static constant unlike {@code SimpleDateFormat}. */
    private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");

    // ── Filter fields ────────────────────────────────────────────
    final JTextField startOffsetField       = new JTextField("", 10);
    final JTextField endOffsetField         = new JTextField("", 10);
    final JTextField percentileField        = new JTextField("90", 10);
    final JTextField transactionSearchField = new JTextField("", 15);
    private final JCheckBox  regexCheckBox  = new JCheckBox("RegEx");

    // ── Table ────────────────────────────────────────────────────
    private final DefaultTableModel tableModel = new DefaultTableModel(ALL_COLUMNS, 0) {
        @Override
        public boolean isCellEditable(int row, int col) {
            return false;
        }
    };
    private final JTable              resultsTable    = new JTable(tableModel);
    private final JCheckBoxMenuItem[] columnMenuItems = new JCheckBoxMenuItem[ALL_COLUMNS.length];
    private final TableColumn[]       allTableColumns = new TableColumn[ALL_COLUMNS.length];

    // ── Bottom controls ──────────────────────────────────────────
    private final JCheckBox saveTableHeaderBox = new JCheckBox("Save Table Header");

    // ── Time info ────────────────────────────────────────────────
    final JTextField startTimeField = new JTextField("", 20);
    final JTextField endTimeField   = new JTextField("", 20);
    final JTextField durationField  = new JTextField("", 20);

    // ── State ────────────────────────────────────────────────────
    private Map<String, SamplingStatCalculator> cachedResults = Collections.emptyMap();
    private List<JTLParser.TimeBucket>          cachedBuckets = Collections.emptyList();
    private String  lastLoadedFilePath;
    private int     sortColumn    = -1;
    private boolean sortAscending = true;
    private boolean suppressReload;

    /** Called when the AI report button is clicked to retrieve scenario metadata. */
    private Supplier<ScenarioMetadata> metadataSupplier = ScenarioMetadata::empty;

    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-report-worker");
        t.setDaemon(true);
        return t;
    });

    // ─────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────

    public AggregateReportPanel() {
        super(new BorderLayout(5, 5));
        setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.add(buildFilterPanel());
        northPanel.add(buildTimeInfoPanel());

        add(northPanel,           BorderLayout.NORTH);
        add(buildTableScrollPane(), BorderLayout.CENTER);
        add(buildBottomPanel(),   BorderLayout.SOUTH);

        storeOriginalColumns();
        setupFieldListeners();
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses and displays the given JTL file. Shows an error dialog on failure.
     *
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath) {
        return loadJtlFile(filePath, false);
    }

    /**
     * Parses and displays the given JTL file.
     *
     * @param showSuccessDialog {@code true} to show a "Loaded N transactions" dialog
     * @return {@code true} on success
     */
    public boolean loadJtlFile(String filePath, boolean showSuccessDialog) {
        lastLoadedFilePath = filePath;
        try {
            JTLParser.FilterOptions opts   = buildFilterOptions();
            JTLParser.ParseResult   result = new JTLParser().parse(filePath, opts);
            cachedResults = result.results;
            cachedBuckets = result.timeBuckets;
            populateTable(cachedResults, opts.percentile);
            updateTimeInfo(result);

            if (showSuccessDialog) {
                int txnCount = Math.max(0, result.results.size() - 1);
                JOptionPane.showMessageDialog(this,
                        "Loaded " + txnCount + " transaction types from JTL file.",
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[AggregateReportPanel] Error loading JTL file: " + filePath + " — " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "Error loading JTL file:\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** Resets all fields and cached data to their initial state. */
    public void clearAll() {
        tableModel.setRowCount(0);
        cachedResults          = Collections.emptyMap();
        cachedBuckets          = Collections.emptyList();
        lastLoadedFilePath     = null;
        startOffsetField.setText("");
        endOffsetField.setText("");
        percentileField.setText("90");
        transactionSearchField.setText("");
        regexCheckBox.setSelected(false);
        clearTimeInfo();
    }

    /**
     * Suppresses automatic JTL reloads while filter fields are being set programmatically
     * (e.g., during {@code ListenerGUI.configure(TestElement)}).
     */
    public void setSuppressReload(boolean suppress) {
        this.suppressReload = suppress;
    }

    /**
     * Sets the supplier invoked when the user clicks "Generate AI Report"
     * to provide scenario-level metadata. Defaults to {@link ScenarioMetadata#empty()}.
     */
    public void setMetadataSupplier(Supplier<ScenarioMetadata> supplier) {
        this.metadataSupplier = supplier != null ? supplier : ScenarioMetadata::empty;
    }

    public String getStartOffset()    { return startOffsetField.getText().trim(); }
    public String getEndOffset()      { return endOffsetField.getText().trim(); }
    public String getPercentileText() { return percentileField.getText().trim(); }

    public void setStartOffset(String value)  { startOffsetField.setText(nullToEmpty(value)); }
    public void setEndOffset(String value)    { endOffsetField.setText(nullToEmpty(value)); }
    public void setPercentile(String value)   {
        percentileField.setText((value == null || value.isBlank()) ? "90" : value);
    }

    // ─────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────

    private JPanel buildFilterPanel() {
        JPanel panel = titledPanel("Filter Settings");
        GridBagConstraints c = defaultConstraints();

        // Row 0 — labels
        c.gridy = 0;
        addLabel(panel, "Start Offset (Seconds)", 0, c);
        addLabel(panel, "End Offset (Seconds)",   1, c);
        addLabel(panel, "Percentile (%)",         2, c);
        addLabel(panel, "Visible Columns",        3, c);

        // Row 1 — inputs
        c.gridy = 1;
        c.fill  = GridBagConstraints.HORIZONTAL;
        c.weightx = 0.25;
        addField(panel, startOffsetField, 0, c);
        addField(panel, endOffsetField,   1, c);
        addField(panel, percentileField,  2, c);
        c.gridx = 3; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        panel.add(buildColumnDropdown(), c);

        // Row 2 — transaction search label (spans all columns)
        c.gridy = 2; c.gridx = 0; c.gridwidth = 4;
        c.fill = GridBagConstraints.NONE; c.weightx = 1.0;
        addLabel(panel, "Transaction Search", c);
        c.gridwidth = 1;

        // Row 3 — search field + regex toggle
        c.gridy = 3; c.gridx = 0; c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0;
        transactionSearchField.setFont(FONT_REGULAR);
        transactionSearchField.setToolTipText(
                "Filter table by transaction name. Supports plain text and RegEx.");
        panel.add(transactionSearchField, c);

        c.gridx = 3; c.gridwidth = 1; c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        regexCheckBox.setFont(FONT_REGULAR);
        regexCheckBox.setToolTipText("Treat search text as a regular expression");
        panel.add(regexCheckBox, c);

        return panel;
    }

    private JButton buildColumnDropdown() {
        JPopupMenu popup = new JPopupMenu();
        for (int i = 0; i < ALL_COLUMNS.length; i++) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(ALL_COLUMNS[i], true);
            item.setFont(FONT_REGULAR);
            if (i == 0) {
                item.setEnabled(false);   // "Transaction Name" column is always visible
            } else {
                final int col = i;
                item.addActionListener(e -> toggleColumnVisibility(col, item.isSelected()));
            }
            columnMenuItems[i] = item;
            popup.add(item);
        }
        JButton btn = new JButton("Select Columns \u25BC");
        btn.setFont(FONT_REGULAR);
        btn.addActionListener(e -> popup.show(btn, 0, btn.getHeight()));
        return btn;
    }

    private JPanel buildTimeInfoPanel() {
        JPanel panel = titledPanel("Test Time Info");
        GridBagConstraints c = defaultConstraints();
        c.gridy = 0;
        addLabel(panel, "Start Date/Time", 0, c);
        addLabel(panel, "End Date/Time",   1, c);
        addLabel(panel, "Duration",        2, c);

        c.gridy = 1; c.fill = GridBagConstraints.HORIZONTAL;
        addReadOnlyField(panel, startTimeField, 0, c);
        addReadOnlyField(panel, endTimeField,   1, c);
        addReadOnlyField(panel, durationField,  2, c);
        return panel;
    }

    private JScrollPane buildTableScrollPane() {
        resultsTable.setFont(FONT_REGULAR);
        resultsTable.getTableHeader().setFont(FONT_HEADER);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setRowHeight(20);

        resultsTable.getTableHeader().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol  = resultsTable.columnAtPoint(e.getPoint());
                if (viewCol < 0) return;
                int modelCol = resultsTable.convertColumnIndexToModel(viewCol);
                if (modelCol == sortColumn) {
                    sortAscending = !sortAscending;
                } else {
                    sortColumn    = modelCol;
                    sortAscending = true;
                }
                repopulateSorted();
            }
        });

        JScrollPane scroll = new JScrollPane(resultsTable);
        scroll.setPreferredSize(new Dimension(900, 250));
        return scroll;
    }

    private JPanel buildBottomPanel() {
        JButton saveBtn = new JButton("Save Table Data");
        saveBtn.setFont(FONT_REGULAR);
        saveBtn.addActionListener(e -> saveTableData());

        saveTableHeaderBox.setFont(FONT_REGULAR);
        saveTableHeaderBox.setSelected(true);

        JButton aiBtn = new JButton("Generate AI Report");
        aiBtn.setFont(FONT_REGULAR);
        aiBtn.setToolTipText(
                "Analyse the loaded JTL data with AI and generate an HTML performance report");
        aiBtn.addActionListener(e -> startAiReportGeneration(aiBtn));

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        panel.add(saveBtn);
        panel.add(saveTableHeaderBox);
        panel.add(aiBtn);
        return panel;
    }

    // ─────────────────────────────────────────────────────────────
    // Field listeners
    // ─────────────────────────────────────────────────────────────

    private void setupFieldListeners() {
        percentileField.getDocument().addDocumentListener(
                (SimpleDocListener) () -> {
                    updatePercentileColumnHeader();
                    reloadJtl();
                });
        SimpleDocListener offsetListener = this::reloadJtl;
        startOffsetField.getDocument().addDocumentListener(offsetListener);
        endOffsetField.getDocument().addDocumentListener(offsetListener);

        transactionSearchField.getDocument().addDocumentListener(
                (SimpleDocListener) this::applySearchFilter);
        regexCheckBox.addActionListener(e -> applySearchFilter());
    }

    private void updatePercentileColumnHeader() {
        String p = percentileField.getText().trim();
        if (p.isEmpty()) p = "90";
        allTableColumns[PERCENTILE_COL_INDEX].setHeaderValue(p + "th Percentile(ms)");
        resultsTable.getTableHeader().repaint();
    }

    private void reloadJtl() {
        if (suppressReload
                || lastLoadedFilePath == null
                || !new File(lastLoadedFilePath).exists()) {
            return;
        }
        try {
            JTLParser.FilterOptions opts   = buildFilterOptions();
            JTLParser.ParseResult   result = new JTLParser().parse(lastLoadedFilePath, opts);
            cachedResults = result.results;
            cachedBuckets = result.timeBuckets;
            populateTable(cachedResults, opts.percentile);
            updateTimeInfo(result);
        } catch (IOException e) {
            System.err.println("[AggregateReportPanel] Reload failed: " + e.getMessage());
        }
    }

    private void applySearchFilter() {
        if (cachedResults.isEmpty()) return;
        populateTable(cachedResults, readPercentile());
    }

    // ─────────────────────────────────────────────────────────────
    // Table population
    // ─────────────────────────────────────────────────────────────

    private void populateTable(Map<String, SamplingStatCalculator> results, int percentile) {
        tableModel.setRowCount(0);
        DecimalFormat df0  = new DecimalFormat("#");
        DecimalFormat df1  = new DecimalFormat("0.0");
        DecimalFormat df2  = new DecimalFormat("0.00");
        double pFraction   = percentile / 100.0;
        String searchPat   = transactionSearchField.getText().trim();
        boolean useRegex   = regexCheckBox.isSelected();

        List<Object[]> dataRows = new ArrayList<>();
        Object[]       totalRow = null;

        for (SamplingStatCalculator calc : results.values()) {
            if (calc.getCount() == 0) continue;
            String label   = calc.getLabel();
            boolean isTotal = TOTAL_LABEL.equals(label);

            if (!isTotal && !TransactionFilter.matches(label, searchPat, useRegex)) continue;

            long total   = calc.getCount();
            long failed  = Math.round(calc.getErrorPercentage() * total);
            Object[] row = {
                    label,
                    total,
                    total - failed,
                    failed,
                    df0.format(calc.getMean()),
                    calc.getMin().intValue(),
                    calc.getMax().intValue(),
                    df0.format(calc.getPercentPoint(pFraction).doubleValue()),
                    df1.format(calc.getStandardDeviation()),
                    df2.format(calc.getErrorPercentage() * 100.0) + "%",
                    String.format("%.1f/sec", calc.getRate())
            };

            if (isTotal) {
                totalRow = row;
            } else {
                dataRows.add(row);
            }
        }

        if (sortColumn >= 0 && sortColumn < ALL_COLUMNS.length) {
            final int     col = sortColumn;
            final boolean asc = sortAscending;
            dataRows.sort((a, b) -> {
                int cmp = compareTableValues(a[col], b[col]);
                return asc ? cmp : -cmp;
            });
        }

        dataRows.forEach(tableModel::addRow);
        if (totalRow != null) tableModel.addRow(totalRow);   // TOTAL is always pinned last
    }

    /** Re-sorts the currently cached data without re-parsing the JTL file. */
    private void repopulateSorted() {
        if (!cachedResults.isEmpty()) {
            populateTable(cachedResults, readPercentile());
        }
    }

    /**
     * Snapshots the currently visible (filtered + sorted) table rows as plain
     * {@code String[]} arrays, excluding the TOTAL row.
     * Must be called on the Swing EDT.
     */
    List<String[]> getVisibleTableRows() {
        List<String[]> rows = new ArrayList<>(tableModel.getRowCount());
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            Object nameCell = tableModel.getValueAt(r, 0);
            if (TOTAL_LABEL.equals(nameCell != null ? nameCell.toString() : "")) continue;
            String[] cells = new String[ALL_COLUMNS.length];
            for (int c = 0; c < ALL_COLUMNS.length; c++) {
                Object v = tableModel.getValueAt(r, c);
                cells[c] = v != null ? v.toString() : "";
            }
            rows.add(cells);
        }
        return Collections.unmodifiableList(rows);
    }

    // ─────────────────────────────────────────────────────────────
    // Column visibility
    // ─────────────────────────────────────────────────────────────

    private void storeOriginalColumns() {
        TableColumnModel cm = resultsTable.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) {
            allTableColumns[i] = cm.getColumn(i);
        }
    }

    private void toggleColumnVisibility(int colIndex, boolean visible) {
        TableColumnModel cm  = resultsTable.getColumnModel();
        TableColumn       col = allTableColumns[colIndex];
        if (visible) {
            int insertAt = 0;
            for (int i = 0; i < colIndex; i++) {
                if (columnMenuItems[i].isSelected()) insertAt++;
            }
            cm.addColumn(col);
            int currentPos = cm.getColumnCount() - 1;
            if (insertAt < currentPos) cm.moveColumn(currentPos, insertAt);
        } else {
            cm.removeColumn(col);
        }
    }

    private List<Integer> getVisibleColumnModelIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < columnMenuItems.length; i++) {
            if (columnMenuItems[i].isSelected()) indices.add(i);
        }
        return indices;
    }

    // ─────────────────────────────────────────────────────────────
    // Time info display
    // ─────────────────────────────────────────────────────────────

    private void updateTimeInfo(JTLParser.ParseResult result) {
        startTimeField.setText(result.startTimeMs > 0 ? formatMs(result.startTimeMs) : "");
        endTimeField.setText(result.endTimeMs   > 0 ? formatMs(result.endTimeMs)   : "");
        durationField.setText(result.durationMs  > 0 ? formatDuration(result.durationMs) : "");
    }

    private void clearTimeInfo() {
        startTimeField.setText("");
        endTimeField.setText("");
        durationField.setText("");
    }

    private static String formatMs(long epochMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                .format(DISPLAY_TIME_FORMAT);
    }

    private static String formatDuration(long durationMs) {
        long totalSec = durationMs / 1000;
        return String.format("%dh %dm %ds",
                totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60);
    }

    // ─────────────────────────────────────────────────────────────
    // CSV export
    // ─────────────────────────────────────────────────────────────

    private void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this,
                    "No data to save. Load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Table Data");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "CSV Files (*.csv)", "csv"));
        fc.setSelectedFile(new File("aggregate_report.csv"));

        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }
            try {
                saveTableToCSV(file);
                JOptionPane.showMessageDialog(this,
                        "Saved to:\n" + file.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                System.err.println("[AggregateReportPanel] Error saving CSV: " + file.getAbsolutePath() + " — " + e.getMessage());
                JOptionPane.showMessageDialog(this,
                        "Error saving file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void saveTableToCSV(File file) throws IOException {
        List<Integer> visibleCols = getVisibleColumnModelIndices();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            if (saveTableHeaderBox.isSelected()) {
                StringBuilder header = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) header.append(',');
                    header.append(escapeCSV(
                            allTableColumns[visibleCols.get(i)].getHeaderValue().toString()));
                }
                writer.write(header.toString());
                writer.newLine();
            }
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) line.append(',');
                    Object val = tableModel.getValueAt(row, visibleCols.get(i));
                    line.append(escapeCSV(val != null ? val.toString() : ""));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    /** RFC 4180 CSV escaping applied to every cell (not just the transaction name column). */
    private static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ─────────────────────────────────────────────────────────────
    // AI report generation
    // ─────────────────────────────────────────────────────────────

    private void startAiReportGeneration(JButton triggerBtn) {
        if (cachedResults.isEmpty() || tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this,
                    "No data available. Please load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String apiKey = AiReportService.readApiKeyFromEnv();
        if (apiKey == null) {
            apiKey = promptForApiKey();
            if (apiKey == null) return;
        }

        // Snapshot all EDT state before handing off to background thread
        final ScenarioMetadata metadata          = metadataSupplier.get();
        final Map<String, SamplingStatCalculator> snapshot = Map.copyOf(cachedResults);
        final List<String[]>                tableSnapshot = getVisibleTableRows();
        final List<JTLParser.TimeBucket>   bucketsSnapshot = List.copyOf(cachedBuckets);
        final int    percentile = readPercentile();
        final String jtlPath    = lastLoadedFilePath;
        final String startTxt   = startTimeField.getText();
        final String endTxt     = endTimeField.getText();
        final String durTxt     = durationField.getText();
        final String finalKey   = apiKey;

        JDialog    progressDialog = buildProgressDialog();
        JLabel     progressLabel  = (JLabel) ((BorderLayout) progressDialog.getContentPane()
                .getLayout()).getLayoutComponent(BorderLayout.CENTER);
        progressDialog.setVisible(true);
        triggerBtn.setEnabled(false);

        final HtmlReportRenderer.RenderConfig config = new HtmlReportRenderer.RenderConfig(
                metadata.users, metadata.scenarioName, metadata.scenarioDesc,
                metadata.threadGroupName, startTxt, endTxt, durTxt, percentile);

        aiExecutor.submit(() -> runAiReport(
                finalKey, snapshot, tableSnapshot, bucketsSnapshot,
                config, jtlPath, progressDialog, progressLabel, triggerBtn));
    }

    private String promptForApiKey() {
        JPasswordField keyField = new JPasswordField(40);
        keyField.setFont(FONT_REGULAR);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel("<html><b>Environment variable '"
                        + AiReportService.ENV_VAR_NAME + "' not found.</b><br><br>"
                        + "Set it permanently:<br>"
                        + "&nbsp;&nbsp;Windows: <tt>setx " + AiReportService.ENV_VAR_NAME
                        + " \"your-key\"</tt><br>"
                        + "&nbsp;&nbsp;Linux/Mac: add <tt>export " + AiReportService.ENV_VAR_NAME
                        + "=your-key</tt> to ~/.bashrc<br><br>"
                        + "Or enter your Groq API key below for this session only:</html>"),
                BorderLayout.NORTH);
        panel.add(keyField, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(this, panel, "API Key Missing",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        String key = new String(keyField.getPassword()).trim();
        if (key.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No API key entered.",
                    "API Key Missing", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return key;
    }

    private JDialog buildProgressDialog() {
        Window parent = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parent, "Generating AI Report",
                Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        JLabel label = new JLabel("Initialising...");
        label.setFont(FONT_REGULAR);
        label.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));
        dialog.add(label, BorderLayout.CENTER);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(340, 90));
        dialog.setLocationRelativeTo(this);
        return dialog;
    }

    private void runAiReport(String apiKey,
                             Map<String, SamplingStatCalculator> results,
                             List<String[]> tableRows,
                             List<JTLParser.TimeBucket> buckets,
                             HtmlReportRenderer.RenderConfig config,
                             String jtlPath,
                             JDialog progressDialog, JLabel progressLabel,
                             JButton triggerBtn) {
        try {
            setProgress(progressLabel, "Building analysis prompt...");
            String prompt = new PromptBuilder().build(
                    results, config.percentile, config.users,
                    config.scenarioName, config.scenarioDesc, config.startTime, config.duration);

            setProgress(progressLabel, "Calling Groq AI (this may take ~30 seconds)...");
            String markdown = new AiReportService(apiKey).generateReport(prompt);

            setProgress(progressLabel, "Rendering HTML report...");
            String htmlPath = new HtmlReportRenderer().render(markdown, jtlPath, config, tableRows, buckets);

            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                triggerBtn.setEnabled(true);
                try {
                    Desktop.getDesktop().browse(new File(htmlPath).toURI());
                } catch (IOException ex) {
                    System.err.println("[AggregateReportPanel] Could not open browser: " + ex.getMessage());
                }
                JOptionPane.showMessageDialog(this,
                        "AI Report saved to:\n" + htmlPath
                                + "\n\nThe report has been opened in your browser.",
                        "Report Generated", JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (IOException ex) {
            System.err.println("[AggregateReportPanel] AI report generation failed: " + ex.getMessage());
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                triggerBtn.setEnabled(true);
                JOptionPane.showMessageDialog(this,
                        "Report generation failed:\n\n" + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            });
        }
    }

    private static void setProgress(JLabel label, String text) {
        SwingUtilities.invokeLater(() -> label.setText(text));
    }

    // ─────────────────────────────────────────────────────────────
    // Filter options
    // ─────────────────────────────────────────────────────────────

    JTLParser.FilterOptions buildFilterOptions() {
        JTLParser.FilterOptions opts = new JTLParser.FilterOptions();
        opts.startOffset = parseIntField(startOffsetField, 0);
        opts.endOffset   = parseIntField(endOffsetField,   0);
        opts.percentile  = readPercentile();
        return opts;
    }

    private int readPercentile() {
        return parseIntField(percentileField, 90);
    }

    private static int parseIntField(JTextField field, int fallback) {
        try {
            String text = field.getText().trim();
            return text.isEmpty() ? fallback : Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Sorting
    // ─────────────────────────────────────────────────────────────

    /**
     * Compares two table cell values: numerically when both can be parsed as numbers
     * (stripping "%" and "/sec" suffixes), otherwise lexicographically.
     */
    private static int compareTableValues(Object a, Object b) {
        double da = parseNumericCell(a);
        double db = parseNumericCell(b);
        if (!Double.isNaN(da) && !Double.isNaN(db)) {
            return Double.compare(da, db);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static double parseNumericCell(Object val) {
        if (val == null) return Double.NaN;
        String s = val.toString().replace("%", "").replace("/sec", "").trim();
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // UI helpers
    // ─────────────────────────────────────────────────────────────

    private static JPanel titledPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        TitledBorder border = new TitledBorder(title);
        border.setTitleFont(FONT_HEADER);
        panel.setBorder(border);
        return panel;
    }

    private static GridBagConstraints defaultConstraints() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets  = new Insets(4, 6, 4, 6);
        c.anchor  = GridBagConstraints.WEST;
        c.weightx = 0.33;
        return c;
    }

    private static void addLabel(JPanel panel, String text, int gridx,
                                 GridBagConstraints c) {
        c.gridx = gridx;
        JLabel label = new JLabel(text);
        label.setFont(FONT_REGULAR);
        panel.add(label, c);
    }

    private static void addLabel(JPanel panel, String text, GridBagConstraints c) {
        JLabel label = new JLabel(text);
        label.setFont(FONT_REGULAR);
        panel.add(label, c);
    }

    private static void addField(JPanel panel, JTextField field, int gridx,
                                 GridBagConstraints c) {
        c.gridx = gridx;
        field.setFont(FONT_REGULAR);
        panel.add(field, c);
    }

    private static void addReadOnlyField(JPanel panel, JTextField field, int gridx,
                                         GridBagConstraints c) {
        c.gridx = gridx;
        field.setFont(FONT_REGULAR);
        field.setEditable(false);
        field.setBackground(new Color(240, 240, 240));
        panel.add(field, c);
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    // ─────────────────────────────────────────────────────────────
    // Inner types
    // ─────────────────────────────────────────────────────────────

    /** Scenario-level metadata passed to the AI report prompt. */
    public static final class ScenarioMetadata {
        public final String scenarioName;
        public final String scenarioDesc;
        public final String users;
        public final String threadGroupName;

        public ScenarioMetadata(String scenarioName, String scenarioDesc,
                                String users, String threadGroupName) {
            this.scenarioName    = nullToEmpty(scenarioName);
            this.scenarioDesc    = nullToEmpty(scenarioDesc);
            this.users           = nullToEmpty(users);
            this.threadGroupName = nullToEmpty(threadGroupName);
        }

        public static ScenarioMetadata empty() {
            return new ScenarioMetadata("", "", "", "");
        }

        private static String nullToEmpty(String s) {
            return s != null ? s : "";
        }
    }

    /**
     * Compact functional interface for Swing {@link DocumentListener} callbacks.
     * Avoids anonymous class boilerplate when all three events share the same action.
     */
    @FunctionalInterface
    interface SimpleDocListener extends DocumentListener {
        void onUpdate();

        @Override default void insertUpdate(DocumentEvent e)  { onUpdate(); }
        @Override default void removeUpdate(DocumentEvent e)  { onUpdate(); }
        @Override default void changedUpdate(DocumentEvent e) { onUpdate(); }
    }
}