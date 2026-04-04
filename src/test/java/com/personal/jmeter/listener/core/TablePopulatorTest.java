package com.personal.jmeter.listener.core;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TablePopulator#buildRowAsStrings}.
 *
 * <p>No Swing, no file system, no network — pure in-memory verification of
 * the static formatting method that is the single source of truth for row
 * data in both the GUI table and the CLI HTML report.</p>
 */
@DisplayName("TablePopulator — buildRowAsStrings")
class TablePopulatorTest {

    @BeforeAll
    static void initJMeter() {
        System.setProperty("java.awt.headless", "true");
        URL propsUrl = TablePopulatorTest.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static SamplingStatCalculator calcWithSamples(String label,
                                                          long[] elapsedMs,
                                                          boolean[] success) {
        SamplingStatCalculator calc = new SamplingStatCalculator(label);
        long ts = System.currentTimeMillis();
        for (int i = 0; i < elapsedMs.length; i++) {
            SampleResult sr = new SampleResult();
            sr.setStampAndTime(ts + i * 100L, elapsedMs[i]);
            sr.setSuccessful(success[i]);
            calc.addSample(sr);
        }
        return calc;
    }

    private static SamplingStatCalculator allPassed(String label, long... elapsedMs) {
        boolean[] success = new boolean[elapsedMs.length];
        for (int i = 0; i < success.length; i++) success[i] = true;
        return calcWithSamples(label, elapsedMs, success);
    }

    // ─────────────────────────────────────────────────────────────
    // Row structure
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal headless {@link TablePopulator} using a real
     * {@link DefaultTableModel} and {@link JTable}. Safe to use in a
     * headless environment — no rendering or display is invoked.
     */
    private static TablePopulator buildPopulator(DefaultTableModel model) {
        JTable table = new JTable(model);
        int colCount = ColumnIndex.ALL_COLUMNS.length;
        TableColumn[] allCols = new TableColumn[colCount];
        JCheckBoxMenuItem[] menuItems = new JCheckBoxMenuItem[colCount];
        for (int i = 0; i < colCount; i++) {
            allCols[i] = new TableColumn(i);
            menuItems[i] = new JCheckBoxMenuItem(ColumnIndex.ALL_COLUMNS[i], true);
        }
        return new TablePopulator(model, table, allCols, menuItems);
    }

    // ─────────────────────────────────────────────────────────────
    // Count, passed, failed columns
    // ─────────────────────────────────────────────────────────────

    private static DefaultTableModel emptyModel() {
        return new DefaultTableModel(ColumnIndex.ALL_COLUMNS, 0);
    }

    // ─────────────────────────────────────────────────────────────
    // Response time columns
    // ─────────────────────────────────────────────────────────────

    private static SamplingStatCalculator calcFor(String label, long elapsed, boolean success) {
        SamplingStatCalculator c = new SamplingStatCalculator(label);
        SampleResult sr = new SampleResult();
        sr.setStampAndTime(System.currentTimeMillis(), elapsed);
        sr.setSuccessful(success);
        c.addSample(sr);
        return c;
    }

    // ─────────────────────────────────────────────────────────────
    // Error rate and throughput columns
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Row structure")
    class RowStructureTests {

        @Test
        @DisplayName("returns exactly 13 elements")
        void returns13Elements() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 300L), 0.90);
            assertEquals(13, row.length);
        }

        @Test
        @DisplayName("first element is the label")
        void firstElementIsLabel() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Checkout", 300L), 0.90);
            assertEquals("Checkout", row[0]);
        }

        @Test
        @DisplayName("no element is null")
        void noElementIsNull() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 300L), 0.90);
            for (int i = 0; i < row.length; i++) {
                assertNotNull(row[i], "Element at index " + i + " must not be null");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Percentile fraction variations
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Count, passed and failed columns")
    class CountTests {

        @Test
        @DisplayName("total count matches number of samples added")
        void totalCountCorrect() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 100L, 200L, 300L), 0.90);
            assertEquals("3", row[1]);
        }

        @Test
        @DisplayName("passed count equals total when no failures")
        void passedCountAllPassed() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 100L, 200L), 0.90);
            assertEquals("2", row[2]); // passed
            assertEquals("0", row[3]); // failed
        }

        @Test
        @DisplayName("failed count reflects error samples")
        void failedCountReflectsErrors() {
            SamplingStatCalculator calc = calcWithSamples(
                    "Login",
                    new long[]{100L, 200L, 300L},
                    new boolean[]{true, false, false});
            String[] row = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertEquals("3", row[1]); // total
            assertEquals("1", row[2]); // passed
            assertEquals("2", row[3]); // failed
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Instance method tests (headless Swing)
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response time columns")
    class ResponseTimeTests {

        @Test
        @DisplayName("avg (mean) is integer-rounded")
        void avgIsIntegerRounded() {
            // Two samples: 100ms and 200ms → avg = 150ms
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 200L), 0.90);
            assertEquals("150", row[4]);
        }

        @Test
        @DisplayName("min is the smallest elapsed")
        void minIsSmallest() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 300L, 500L), 0.90);
            assertEquals("100", row[5]);
        }

        @Test
        @DisplayName("max is the largest elapsed")
        void maxIsLargest() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 300L, 500L), 0.90);
            assertEquals("500", row[6]);
        }

        @Test
        @DisplayName("percentile column index is 7")
        void percentileColumnIndex() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 200L, 300L), 0.90);
            assertNotNull(row[7], "Percentile value must not be null");
            assertFalse(row[7].isBlank(), "Percentile value must not be blank");
        }

        @Test
        @DisplayName("std deviation has one decimal place")
        void stdDevOneDecimalPlace() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Tx", 100L, 200L, 300L), 0.90);
            assertTrue(row[8].matches("\\d+\\.\\d"),
                    "Std deviation must match pattern 'N.N', got: " + row[8]);
        }
    }

    @Nested
    @DisplayName("Error rate and throughput columns")
    class ErrorAndThroughputTests {

        @Test
        @DisplayName("error rate is 0.00% when no failures")
        void errorRateZeroWhenNoFailures() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 200L, 300L), 0.90);
            assertEquals("0.00%", row[9]);
        }

        @Test
        @DisplayName("error rate ends with percent sign")
        void errorRateEndsWithPercent() {
            SamplingStatCalculator calc = calcWithSamples(
                    "Login",
                    new long[]{100L, 200L},
                    new boolean[]{true, false});
            String[] row = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertTrue(row[9].endsWith("%"), "Error rate must end with '%', got: " + row[9]);
        }

        @Test
        @DisplayName("throughput ends with /sec")
        void throughputEndsWithPerSec() {
            String[] row = TablePopulator.buildRowAsStrings(
                    allPassed("Login", 200L, 300L), 0.90);
            assertTrue(row[10].endsWith("/sec"),
                    "Throughput must end with '/sec', got: " + row[10]);
        }

        @Test
        @DisplayName("100% error rate when all samples fail")
        void fullErrorRate() {
            SamplingStatCalculator calc = calcWithSamples(
                    "Login",
                    new long[]{100L, 200L},
                    new boolean[]{false, false});
            String[] row = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertEquals("100.00%", row[9]);
            assertEquals("0", row[2]); // passed = 0
            assertEquals("2", row[3]); // failed = 2
        }
    }

    @Nested
    @DisplayName("Percentile fraction variations")
    class PercentileFractionTests {

        @Test
        @DisplayName("p50 and p90 both produce non-null non-blank values")
        void differentPercentilesNonNull() {
            SamplingStatCalculator calc = allPassed("Tx",
                    100L, 150L, 200L, 250L, 300L, 350L, 400L, 450L, 500L, 600L);
            String[] rowP50 = TablePopulator.buildRowAsStrings(calc, 0.50);
            String[] rowP90 = TablePopulator.buildRowAsStrings(calc, 0.90);
            assertFalse(rowP50[7].isBlank());
            assertFalse(rowP90[7].isBlank());
        }
    }

    @Nested
    @DisplayName("Instance — initial state")
    class InstanceInitialStateTests {

        @Test
        @DisplayName("getSortColumn returns -1 (unsorted) on construction")
        void initialSortColumnIsMinusOne() {
            TablePopulator pop = buildPopulator(emptyModel());
            assertEquals(-1, pop.getSortColumn());
        }

        @Test
        @DisplayName("isSortAscending returns true (default) on construction")
        void initialSortDirectionIsAscending() {
            TablePopulator pop = buildPopulator(emptyModel());
            assertTrue(pop.isSortAscending());
        }
    }

    @Nested
    @DisplayName("Instance — getVisibleRows")
    class GetVisibleRowsTests {

        @Test
        @DisplayName("returns empty list when model has no rows")
        void emptyModelReturnsEmptyList() {
            TablePopulator pop = buildPopulator(emptyModel());
            assertTrue(pop.getVisibleRows().isEmpty());
        }

        @Test
        @DisplayName("returns unmodifiable list")
        void returnsUnmodifiableList() {
            TablePopulator pop = buildPopulator(emptyModel());
            List<String[]> rows = pop.getVisibleRows();
            assertThrows(UnsupportedOperationException.class, () -> rows.add(new String[]{}));
        }

        @Test
        @DisplayName("TOTAL row is excluded from visible rows")
        void totalRowExcluded() {
            DefaultTableModel model = emptyModel();
            // Add a TOTAL row manually
            Object[] totalRow = new Object[ColumnIndex.ALL_COLUMNS.length];
            totalRow[0] = "TOTAL";
            model.addRow(totalRow);

            TablePopulator pop = buildPopulator(model);
            assertTrue(pop.getVisibleRows().isEmpty(),
                    "TOTAL row must be excluded from getVisibleRows()");
        }

        @Test
        @DisplayName("non-TOTAL rows are included in visible rows")
        void nonTotalRowIncluded() {
            DefaultTableModel model = emptyModel();
            Object[] row = new Object[ColumnIndex.ALL_COLUMNS.length];
            row[0] = "Login";
            model.addRow(row);

            TablePopulator pop = buildPopulator(model);
            assertEquals(1, pop.getVisibleRows().size());
            assertEquals("Login", pop.getVisibleRows().get(0)[0]);
        }
    }

    @Nested
    @DisplayName("Instance — populate")
    class PopulateTests {

        @Test
        @DisplayName("populate with no results produces empty table")
        void populateEmptyResults() {
            DefaultTableModel model = emptyModel();
            TablePopulator pop = buildPopulator(model);
            pop.populate(new LinkedHashMap<>(), 90, "", false, false);
            assertEquals(0, model.getRowCount());
        }

        @Test
        @DisplayName("populate adds non-TOTAL transactions to table")
        void populateAddsRows() {
            DefaultTableModel model = emptyModel();
            TablePopulator pop = buildPopulator(model);

            Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("Login", calcFor("Login", 300L, true));
            results.put("Checkout", calcFor("Checkout", 500L, true));

            pop.populate(results, 90, "", false, false);
            // 2 data rows, no TOTAL
            assertEquals(2, model.getRowCount());
        }

        @Test
        @DisplayName("populate places TOTAL row last")
        void totalRowLast() {
            DefaultTableModel model = emptyModel();
            TablePopulator pop = buildPopulator(model);

            SamplingStatCalculator total = new SamplingStatCalculator("TOTAL");
            SamplingStatCalculator login = new SamplingStatCalculator("Login");
            SampleResult sr = new SampleResult();
            sr.setStampAndTime(System.currentTimeMillis(), 200L);
            sr.setSuccessful(true);
            total.addSample(sr);
            login.addSample(sr);

            Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("TOTAL", total);
            results.put("Login", login);

            pop.populate(results, 90, "", false, false);
            assertEquals(2, model.getRowCount());
            assertEquals("TOTAL", model.getValueAt(1, 0).toString());
        }

        @Test
        @DisplayName("populate with include filter shows only matching rows")
        void populateWithIncludeFilter() {
            DefaultTableModel model = emptyModel();
            TablePopulator pop = buildPopulator(model);

            Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("Login", calcFor("Login", 300L, true));
            results.put("Checkout", calcFor("Checkout", 500L, true));

            pop.populate(results, 90, "Login", false, false);
            assertEquals(1, model.getRowCount());
            assertEquals("Login", model.getValueAt(0, 0).toString());
        }

        @Test
        @DisplayName("populate with exclude filter hides matching rows")
        void populateWithExcludeFilter() {
            DefaultTableModel model = emptyModel();
            TablePopulator pop = buildPopulator(model);

            Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("Login", calcFor("Login", 300L, true));
            results.put("Checkout", calcFor("Checkout", 500L, true));

            pop.populate(results, 90, "Login", false, true);
            assertEquals(1, model.getRowCount());
            assertEquals("Checkout", model.getValueAt(0, 0).toString());
        }

        @Test
        @DisplayName("populate skips zero-count calculators")
        void populateSkipsZeroCount() {
            DefaultTableModel model = emptyModel();
            TablePopulator pop = buildPopulator(model);

            Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("Empty", new SamplingStatCalculator("Empty")); // no samples
            results.put("Login", calcFor("Login", 300L, true));

            pop.populate(results, 90, "", false, false);
            assertEquals(1, model.getRowCount());
        }
    }
}