package com.personal.jmeter.listener;

import com.personal.jmeter.parser.JTLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/**
 * Handles CSV export of the results table for the JAAR.
 *
 * <p>Extracted from {@link AggregateReportPanel} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: CSV file writing only.</p>
 *
 * <p>When SLA thresholds are configured, two extra columns ({@code Error% SLA}
 * and {@code RT SLA}) are appended after the visible data columns, each cell
 * containing {@code PASS}, {@code FAIL}, or {@code -} (not configured / TOTAL row).
 * This mirrors the SLA status columns in the HTML report.</p>
 *
 * <p>Dependencies are injected at construction time to keep this class testable
 * without a live {@link AggregateReportPanel}.</p>
 */
final class CsvExporter {

    private static final Logger log = LoggerFactory.getLogger(CsvExporter.class);

    private final JComponent parent;
    private final DefaultTableModel tableModel;
    private final TableColumn[] allTableColumns;
    private final JCheckBoxMenuItem[] columnMenuItems;
    private final Supplier<SlaConfig> slaConfigSupplier;

    /**
     * Constructs the exporter with all required references from the parent panel.
     *
     * @param parent            the Swing parent for dialogs; must not be null
     * @param tableModel        the table data model; must not be null
     * @param allTableColumns   all column objects (visible and hidden); must not be null
     * @param columnMenuItems   column visibility checkboxes; must not be null
     * @param slaConfigSupplier live supplier of the current SLA config; must not be null
     */
    CsvExporter(JComponent parent,
                DefaultTableModel tableModel,
                TableColumn[] allTableColumns,
                JCheckBoxMenuItem[] columnMenuItems,
                Supplier<SlaConfig> slaConfigSupplier) {
        this.parent = parent;
        this.tableModel = tableModel;
        this.allTableColumns = allTableColumns;
        this.columnMenuItems = columnMenuItems;
        this.slaConfigSupplier = slaConfigSupplier;
    }

    /**
     * Opens a save dialog and writes the currently visible table data to a CSV file.
     * Shows a success or error message dialog on completion.
     */
    void saveTableData() {
        if (tableModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(parent,
                    "No data to save. Load a JTL file first.",
                    "No Data", JOptionPane.WARNING_MESSAGE);
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save Table Data");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "CSV Files (*.csv)", "csv"));
        fc.setSelectedFile(new File("aggregate_report.csv"));

        if (fc.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
            File file = fc.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".csv")) {
                file = new File(file.getAbsolutePath() + ".csv");
            }
            try {
                saveTableToCSV(file);
                JOptionPane.showMessageDialog(parent,
                        "Saved to:\n" + file.getAbsolutePath(),
                        "Success", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException e) {
                log.error("saveTableData: error saving CSV. filePath={}, reason={}",
                        file.getAbsolutePath(), e.getMessage(), e);
                JOptionPane.showMessageDialog(parent,
                        "Error saving file:\n" + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // Package-private to allow direct unit testing without Swing file-chooser dialogs.
    void saveTableToCSV(File file) throws IOException {
        List<Integer> visibleCols = getVisibleColumnModelIndices();
        SlaConfig sla = slaConfigSupplier.get();
        boolean hasErrorSla = sla.isErrorPctEnabled();
        boolean hasRtSla    = sla.isRtEnabled();
        boolean hasSla      = hasErrorSla || hasRtSla;

        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                        java.nio.file.Files.newOutputStream(file.toPath(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING),
                        StandardCharsets.UTF_8))) {

            // ── Header row ────────────────────────────────────────────
            StringBuilder header = new StringBuilder();
            for (int i = 0; i < visibleCols.size(); i++) {
                if (i > 0) header.append(',');
                header.append(escapeCSV(
                        allTableColumns[visibleCols.get(i)].getHeaderValue().toString()));
            }
            if (hasSla) {
                header.append(',').append(escapeCSV("Error% SLA"));
                header.append(',').append(escapeCSV("RT SLA"));
            }
            writer.write(header.toString());
            writer.newLine();

            // ── Data rows ─────────────────────────────────────────────
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < visibleCols.size(); i++) {
                    if (i > 0) line.append(',');
                    Object val = tableModel.getValueAt(row, visibleCols.get(i));
                    line.append(escapeCSV(val != null ? val.toString() : ""));
                }
                if (hasSla) {
                    line.append(',').append(evaluateErrorSla(row, sla));
                    line.append(',').append(evaluateRtSla(row, sla));
                }
                writer.write(line.toString());
                writer.newLine();
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SLA evaluation helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Evaluates the error-rate SLA for one row.
     *
     * @return {@code "PASS"}, {@code "FAIL"}, or {@code "-"}
     */
    private String evaluateErrorSla(int row, SlaConfig sla) {
        if (isTotalRow(row)) return "-";
        if (!sla.isErrorPctEnabled()) return "-";
        double observed = parseErrorRate(
                tableModel.getValueAt(row, AggregateReportPanel.ERROR_RATE_COL_INDEX));
        return observed > sla.errorPctThreshold ? "FAIL" : "PASS";
    }

    /**
     * Evaluates the response-time SLA for one row.
     *
     * @return {@code "PASS"}, {@code "FAIL"}, or {@code "-"}
     */
    private String evaluateRtSla(int row, SlaConfig sla) {
        if (isTotalRow(row)) return "-";
        if (!sla.isRtEnabled()) return "-";
        int rtCol = (sla.rtMetric == SlaConfig.RtMetric.AVG)
                ? AggregateReportPanel.AVG_COL_INDEX
                : AggregateReportPanel.PERCENTILE_COL_INDEX;
        double observed = parseDouble(tableModel.getValueAt(row, rtCol));
        return observed > sla.rtThresholdMs ? "FAIL" : "PASS";
    }

    /**
     * Returns {@code true} if the given row is the TOTAL row.
     */
    private boolean isTotalRow(int row) {
        Object nameCell = tableModel.getValueAt(row, AggregateReportPanel.NAME_COL_INDEX);
        return JTLParser.TOTAL_LABEL.equals(nameCell != null ? nameCell.toString() : "");
    }

    // ─────────────────────────────────────────────────────────────
    // Parse helpers (mirrors SlaRowRenderer)
    // ─────────────────────────────────────────────────────────────

    /** Strips the trailing {@code %} from an Error Rate cell before parsing. */
    private static double parseErrorRate(Object val) {
        if (val == null) return 0;
        try {
            return Double.parseDouble(val.toString().replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses a plain numeric cell value. */
    private static double parseDouble(Object val) {
        if (val == null) return 0;
        try {
            return Double.parseDouble(val.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<Integer> getVisibleColumnModelIndices() {
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (int i = 0; i < columnMenuItems.length; i++) {
            if (columnMenuItems[i].isSelected()) indices.add(i);
        }
        return indices;
    }

    /**
     * RFC 4180 CSV escaping applied to every cell.
     *
     * @param value cell value (may be null)
     * @return escaped string safe for embedding in CSV
     */
    static String escapeCSV(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}