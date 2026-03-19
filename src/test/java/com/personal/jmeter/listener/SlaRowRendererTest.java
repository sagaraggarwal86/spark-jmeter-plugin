package com.personal.jmeter.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SlaRowRenderer}.
 *
 * <p>Tests the breach-highlighting logic via a headless {@link JTable} backed
 * by a {@link DefaultTableModel}. No display server required — Swing renderers
 * return configured {@link Component} objects without needing to paint.</p>
 */
@DisplayName("SlaRowRenderer")
class SlaRowRendererTest {

    /** Column indices matching {@link AggregateReportPanel} constants. */
    private static final int NAME_COL       = AggregateReportPanel.NAME_COL_INDEX;       // 0
    private static final int AVG_COL        = AggregateReportPanel.AVG_COL_INDEX;        // 4
    private static final int PNN_COL        = AggregateReportPanel.PERCENTILE_COL_INDEX; // 7
    private static final int ERROR_RATE_COL = AggregateReportPanel.ERROR_RATE_COL_INDEX; // 9

    private DefaultTableModel model;
    private JTable table;
    private SlaConfig[] currentSla;
    private SlaRowRenderer renderer;

    @BeforeEach
    void setUp() {
        model = new DefaultTableModel(AggregateReportPanel.ALL_COLUMNS, 0);
        table = new JTable(model);
        currentSla = new SlaConfig[]{ SlaConfig.disabled(90) };
        renderer = new SlaRowRenderer(
                () -> currentSla[0],
                ERROR_RATE_COL, AVG_COL, PNN_COL, NAME_COL);
        table.setDefaultRenderer(Object.class, renderer);
    }

    /** Standard data row matching ALL_COLUMNS (13 elements). */
    private void addRow(String name, String avg, String p90, String errorRate) {
        model.addRow(new Object[]{name, 100L, 95L, 5L, avg, "10", "500", p90,
                "50.0", errorRate, "10.0/sec", "5.00", "512.0"});
    }

    /** Renders the cell and returns the Component for assertion. */
    private Component render(int row, int col) {
        return renderer.getTableCellRendererComponent(
                table, model.getValueAt(row, col), false, false, row, col);
    }

    private boolean isBreachHighlighted(Component c) {
        return c.getForeground().equals(Color.RED)
                && c.getFont().isBold();
    }

    private boolean isNormalStyle(Component c) {
        return !c.getForeground().equals(Color.RED)
                && !c.getFont().isBold();
    }

    // ─────────────────────────────────────────────────────────────
    // No SLA configured
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("no SLA configured")
    class NoSla {

        @Test
        @DisplayName("error rate cell is not highlighted")
        void errorRateNotHighlighted() {
            addRow("Login", "200", "300", "8.00%");
            assertTrue(isNormalStyle(render(0, ERROR_RATE_COL)));
        }

        @Test
        @DisplayName("avg cell is not highlighted")
        void avgNotHighlighted() {
            addRow("Login", "5000", "300", "1.00%");
            assertTrue(isNormalStyle(render(0, AVG_COL)));
        }

        @Test
        @DisplayName("pnn cell is not highlighted")
        void pnnNotHighlighted() {
            addRow("Login", "200", "5000", "1.00%");
            assertTrue(isNormalStyle(render(0, PNN_COL)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error rate SLA
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("error rate SLA")
    class ErrorRateSla {

        @BeforeEach
        void enableErrorSla() {
            currentSla[0] = SlaConfig.from("5", "", SlaConfig.RtMetric.PNN, 90);
        }

        @Test
        @DisplayName("breach → error rate cell is red bold")
        void breachHighlighted() {
            addRow("Login", "200", "300", "8.00%");
            assertTrue(isBreachHighlighted(render(0, ERROR_RATE_COL)));
        }

        @Test
        @DisplayName("within → error rate cell is normal")
        void withinNotHighlighted() {
            addRow("Login", "200", "300", "2.00%");
            assertTrue(isNormalStyle(render(0, ERROR_RATE_COL)));
        }

        @Test
        @DisplayName("exactly at threshold → not highlighted (> not >=)")
        void exactThresholdNotHighlighted() {
            addRow("Login", "200", "300", "5.00%");
            assertTrue(isNormalStyle(render(0, ERROR_RATE_COL)));
        }

        @Test
        @DisplayName("non-error-rate column is not highlighted even when error SLA breaches")
        void otherColumnNotHighlighted() {
            addRow("Login", "200", "300", "8.00%");
            assertTrue(isNormalStyle(render(0, AVG_COL)));
            assertTrue(isNormalStyle(render(0, NAME_COL)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RT SLA — PNN metric
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RT SLA — PNN metric")
    class RtSlaPnn {

        @BeforeEach
        void enableRtSlaPnn() {
            currentSla[0] = SlaConfig.from("", "2000", SlaConfig.RtMetric.PNN, 90);
        }

        @Test
        @DisplayName("P90 breach → pnn cell is red bold")
        void pnnBreachHighlighted() {
            addRow("Login", "200", "3000", "1.00%");
            assertTrue(isBreachHighlighted(render(0, PNN_COL)));
        }

        @Test
        @DisplayName("P90 within → pnn cell is normal")
        void pnnWithinNotHighlighted() {
            addRow("Login", "200", "1500", "1.00%");
            assertTrue(isNormalStyle(render(0, PNN_COL)));
        }

        @Test
        @DisplayName("avg column not highlighted even when P90 breaches")
        void avgNotHighlightedOnPnnBreach() {
            addRow("Login", "3000", "3000", "1.00%");
            assertTrue(isNormalStyle(render(0, AVG_COL)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RT SLA — AVG metric
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("RT SLA — AVG metric")
    class RtSlaAvg {

        @BeforeEach
        void enableRtSlaAvg() {
            currentSla[0] = SlaConfig.from("", "2000", SlaConfig.RtMetric.AVG, 90);
        }

        @Test
        @DisplayName("Avg breach → avg cell is red bold")
        void avgBreachHighlighted() {
            addRow("Login", "3000", "300", "1.00%");
            assertTrue(isBreachHighlighted(render(0, AVG_COL)));
        }

        @Test
        @DisplayName("Avg within → avg cell is normal")
        void avgWithinNotHighlighted() {
            addRow("Login", "1500", "300", "1.00%");
            assertTrue(isNormalStyle(render(0, AVG_COL)));
        }

        @Test
        @DisplayName("pnn column not highlighted even when Avg breaches")
        void pnnNotHighlightedOnAvgBreach() {
            addRow("Login", "3000", "3000", "1.00%");
            assertTrue(isNormalStyle(render(0, PNN_COL)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // TOTAL row
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TOTAL row")
    class TotalRow {

        @Test
        @DisplayName("TOTAL row error rate cell is never highlighted")
        void totalErrorRateNeverHighlighted() {
            currentSla[0] = SlaConfig.from("5", "", SlaConfig.RtMetric.PNN, 90);
            model.addRow(new Object[]{"TOTAL", 100L, 90L, 10L, "200", "10", "500", "300",
                    "50.0", "10.00%", "10.0/sec", "5.00", "512.0"});
            assertTrue(isNormalStyle(render(0, ERROR_RATE_COL)));
        }

        @Test
        @DisplayName("TOTAL row RT cell is never highlighted")
        void totalRtNeverHighlighted() {
            currentSla[0] = SlaConfig.from("", "100", SlaConfig.RtMetric.PNN, 90);
            model.addRow(new Object[]{"TOTAL", 100L, 90L, 10L, "5000", "10", "500", "5000",
                    "50.0", "1.00%", "10.0/sec", "5.00", "512.0"});
            assertTrue(isNormalStyle(render(0, PNN_COL)));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Selected cell
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("selected cell")
    class SelectedCell {

        @Test
        @DisplayName("selected cell is not breach-highlighted even when breaching")
        void selectedNotHighlighted() {
            currentSla[0] = SlaConfig.from("5", "", SlaConfig.RtMetric.PNN, 90);
            addRow("Login", "200", "300", "8.00%");
            Component c = renderer.getTableCellRendererComponent(
                    table, model.getValueAt(0, ERROR_RATE_COL), true, false, 0, ERROR_RATE_COL);
            // When selected, renderer returns early before applying breach colour
            assertFalse(c.getForeground().equals(Color.RED) && c.getFont().isBold());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Edge cases — parse robustness
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parse robustness")
    class ParseRobustness {

        @Test
        @DisplayName("null cell value does not throw")
        void nullCellNoThrow() {
            currentSla[0] = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            model.addRow(new Object[]{"Login", 100L, 95L, 5L, null, "10", "500", null,
                    "50.0", null, "10.0/sec", "5.00", "512.0"});
            assertDoesNotThrow(() -> render(0, ERROR_RATE_COL));
            assertDoesNotThrow(() -> render(0, PNN_COL));
        }

        @Test
        @DisplayName("non-numeric cell value does not throw")
        void nonNumericNoThrow() {
            currentSla[0] = SlaConfig.from("5", "2000", SlaConfig.RtMetric.PNN, 90);
            model.addRow(new Object[]{"Login", 100L, 95L, 5L, "N/A", "10", "500", "N/A",
                    "50.0", "N/A%", "10.0/sec", "5.00", "512.0"});
            assertDoesNotThrow(() -> render(0, ERROR_RATE_COL));
            assertDoesNotThrow(() -> render(0, PNN_COL));
        }
    }
}
