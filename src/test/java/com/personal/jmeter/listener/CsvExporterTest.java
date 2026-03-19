package com.personal.jmeter.listener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CsvExporter}.
 *
 * <p>Tests cover RFC 4180 CSV escaping and SLA status column generation.
 * SLA column tests use a headless {@link DefaultTableModel} with known data
 * and verify CSV file content via {@link TempDir}.</p>
 */
@DisplayName("CsvExporter")
class CsvExporterTest {

    // ─────────────────────────────────────────────────────────────
    // escapeCSV — existing tests
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("escapeCSV — null and empty input")
    class NullAndEmptyTests {

        @Test
        @DisplayName("null returns empty string")
        void nullReturnsEmpty() {
            assertEquals("", CsvExporter.escapeCSV(null));
        }

        @Test
        @DisplayName("empty string returns empty string")
        void emptyReturnsEmpty() {
            assertEquals("", CsvExporter.escapeCSV(""));
        }
    }

    @Nested
    @DisplayName("escapeCSV — plain values")
    class PlainValueTests {

        @Test
        @DisplayName("plain text is returned unchanged")
        void plainTextUnchanged() {
            assertEquals("Login", CsvExporter.escapeCSV("Login"));
        }

        @Test
        @DisplayName("numeric string is returned unchanged")
        void numericUnchanged() {
            assertEquals("12345", CsvExporter.escapeCSV("12345"));
        }

        @Test
        @DisplayName("value with spaces but no special chars is returned unchanged")
        void spacesUnchanged() {
            assertEquals("Login Flow", CsvExporter.escapeCSV("Login Flow"));
        }
    }

    @Nested
    @DisplayName("escapeCSV — values requiring quoting")
    class QuotingTests {

        @Test
        @DisplayName("value containing comma is wrapped in double quotes")
        void commaIsQuoted() {
            assertEquals("\"a,b\"", CsvExporter.escapeCSV("a,b"));
        }

        @Test
        @DisplayName("value containing double-quote has quote escaped and is wrapped")
        void doubleQuoteIsEscaped() {
            assertEquals("\"say \"\"hello\"\"\"", CsvExporter.escapeCSV("say \"hello\""));
        }

        @Test
        @DisplayName("value containing newline is wrapped in double quotes")
        void newlineIsQuoted() {
            assertEquals("\"line1\nline2\"", CsvExporter.escapeCSV("line1\nline2"));
        }

        @Test
        @DisplayName("value containing both comma and quote is handled correctly")
        void commaAndQuoteCombined() {
            assertEquals("\"a,\"\"b\"\"\"", CsvExporter.escapeCSV("a,\"b\""));
        }

        @Test
        @DisplayName("value with only a double-quote is escaped and wrapped")
        void singleQuoteOnly() {
            assertEquals("\"\"\"\"", CsvExporter.escapeCSV("\""));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // SLA columns in CSV output
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("saveTableToCSV — SLA columns")
    class SlaColumnTests {

        @TempDir
        Path tempDir;

        /**
         * Builds a minimal CsvExporter backed by a DefaultTableModel with
         * the same column structure as AggregateReportPanel.ALL_COLUMNS.
         */
        private CsvExporter buildExporter(DefaultTableModel model, SlaConfig sla) {
            JCheckBoxMenuItem[] items = new JCheckBoxMenuItem[AggregateReportPanel.ALL_COLUMNS.length];
            TableColumn[] cols = new TableColumn[AggregateReportPanel.ALL_COLUMNS.length];
            for (int i = 0; i < items.length; i++) {
                items[i] = new JCheckBoxMenuItem(AggregateReportPanel.ALL_COLUMNS[i], true);
                cols[i] = new TableColumn(i);
                cols[i].setHeaderValue(AggregateReportPanel.ALL_COLUMNS[i]);
            }
            return new CsvExporter(new JPanel(), model, cols, items, () -> sla);
        }

        /** Creates a model with ALL_COLUMNS and adds the given rows. */
        private DefaultTableModel modelWith(Object[]... rows) {
            DefaultTableModel model = new DefaultTableModel(AggregateReportPanel.ALL_COLUMNS, 0);
            for (Object[] row : rows) model.addRow(row);
            return model;
        }

        /** Standard data row: name, count, passed, failed, avg, min, max, p90, stddev, errRate, tps, kb, avgBytes */
        private Object[] dataRow(String name, String avg, String p90, String errorRate) {
            return new Object[]{name, 100L, 95L, 5L, avg, "10", "500", p90, "50.0", errorRate, "10.0/sec", "5.00", "512.0"};
        }

        private Object[] totalRow(String avg, String p90, String errorRate) {
            return new Object[]{"TOTAL", 200L, 190L, 10L, avg, "10", "500", p90, "50.0", errorRate, "20.0/sec", "10.00", "512.0"};
        }

        private List<String> writeCsv(CsvExporter exporter) throws IOException {
            File file = tempDir.resolve("test.csv").toFile();
            exporter.saveTableToCSV(file);
            return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        }

        // ── No SLA configured ────────────────────────────────────

        @Test
        @DisplayName("no SLA configured → no extra columns")
        void noSlaNoExtraColumns() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "300", "1.00%"));
            CsvExporter exporter = buildExporter(model, SlaConfig.disabled(90));
            List<String> lines = writeCsv(exporter);

            // Header should NOT contain SLA columns
            assertFalse(lines.get(0).contains("Error% SLA"));
            assertFalse(lines.get(0).contains("RT SLA"));
        }

        // ── Error SLA only ───────────────────────────────────────

        @Test
        @DisplayName("error SLA breach → FAIL in Error% SLA column")
        void errorSlaBreach() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "300", "5.50%"));
            SlaConfig sla = SlaConfig.from("5", "", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(0).endsWith(",Error% SLA,RT SLA"));
            assertTrue(lines.get(1).endsWith(",FAIL,-"));
        }

        @Test
        @DisplayName("error SLA within → PASS in Error% SLA column")
        void errorSlaPass() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "300", "2.00%"));
            SlaConfig sla = SlaConfig.from("5", "", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",PASS,-"));
        }

        // ── RT SLA only (PNN metric) ─────────────────────────────

        @Test
        @DisplayName("RT SLA breach on P90 → FAIL in RT SLA column")
        void rtSlaPnnBreach() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "3000", "1.00%"));
            SlaConfig sla = SlaConfig.from("", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",-,FAIL"));
        }

        @Test
        @DisplayName("RT SLA within on P90 → PASS in RT SLA column")
        void rtSlaPnnPass() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "1500", "1.00%"));
            SlaConfig sla = SlaConfig.from("", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",-,PASS"));
        }

        // ── RT SLA on AVG metric ─────────────────────────────────

        @Test
        @DisplayName("RT SLA breach on Avg → FAIL in RT SLA column")
        void rtSlaAvgBreach() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "2500", "3000", "1.00%"));
            SlaConfig sla = SlaConfig.from("", "2000", SlaConfig.RtMetric.AVG, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",-,FAIL"));
        }

        @Test
        @DisplayName("RT SLA within on Avg → PASS in RT SLA column")
        void rtSlaAvgPass() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "1500", "3000", "1.00%"));
            SlaConfig sla = SlaConfig.from("", "2000", SlaConfig.RtMetric.AVG, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",-,PASS"));
        }

        // ── Both SLAs ────────────────────────────────────────────

        @Test
        @DisplayName("both SLAs configured — mixed PASS/FAIL")
        void bothSlasMixed() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "3000", "2.00%"));
            SlaConfig sla = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            // Error within (2% ≤ 5%), RT breach (3000 > 2000)
            assertTrue(lines.get(1).endsWith(",PASS,FAIL"));
        }

        @Test
        @DisplayName("both SLAs — both FAIL")
        void bothSlasBothFail() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "3000", "8.00%"));
            SlaConfig sla = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",FAIL,FAIL"));
        }

        @Test
        @DisplayName("both SLAs — both PASS")
        void bothSlasBothPass() throws IOException {
            DefaultTableModel model = modelWith(dataRow("Login", "200", "1500", "2.00%"));
            SlaConfig sla = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertTrue(lines.get(1).endsWith(",PASS,PASS"));
        }

        // ── TOTAL row ────────────────────────────────────────────

        @Test
        @DisplayName("TOTAL row always gets dash regardless of values")
        void totalRowAlwaysDash() throws IOException {
            DefaultTableModel model = modelWith(
                    dataRow("Login", "200", "3000", "8.00%"),
                    totalRow("200", "3000", "8.00%"));
            SlaConfig sla = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            // Row 1 = Login (should FAIL)
            assertTrue(lines.get(1).contains(",FAIL,FAIL"));
            // Row 2 = TOTAL (should be dash)
            assertTrue(lines.get(2).endsWith(",-,-"));
        }

        // ── Multiple rows ────────────────────────────────────────

        @Test
        @DisplayName("multiple rows with different SLA outcomes")
        void multipleRows() throws IOException {
            DefaultTableModel model = modelWith(
                    dataRow("Login",    "200",  "1500", "2.00%"),
                    dataRow("Checkout", "200",  "3000", "8.00%"),
                    totalRow("200", "2000", "5.00%"));
            SlaConfig sla = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            List<String> lines = writeCsv(buildExporter(model, sla));

            assertEquals(4, lines.size()); // header + 2 data + TOTAL
            assertTrue(lines.get(1).endsWith(",PASS,PASS"));      // Login: err 2%≤5%, p90 1500≤2000
            assertTrue(lines.get(2).endsWith(",FAIL,FAIL"));      // Checkout: err 8%>5%, p90 3000>2000
            assertTrue(lines.get(3).endsWith(",-,-"));            // TOTAL
        }
    }
}