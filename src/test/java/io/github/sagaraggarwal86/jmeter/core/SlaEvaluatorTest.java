package io.github.sagaraggarwal86.jmeter.core;

import io.github.sagaraggarwal86.jmeter.listener.core.SlaEvaluator;
import io.github.sagaraggarwal86.jmeter.listener.core.SlaEvaluator.SlaResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SlaEvaluator}.
 *
 * <p>Pure static methods — no Swing, no file system, no network.</p>
 */
@DisplayName("SlaEvaluator")
class SlaEvaluatorTest {

    // Row layout matches ColumnIndex: [0]=name, [4]=avg, [7]=pnn, [9]=errRate, [10]=tps
    private static String[] row(String name, String avg, String pnn, String errRate, String tps) {
        return new String[]{name, "100", "95", "5", avg, "10", "500", pnn, "50.0", errRate, tps, "5.00", "512"};
    }

    // ─────────────────────────────────────────────────────────────
    // SlaResult record
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SlaResult record")
    class SlaResultTests {

        @Test
        @DisplayName("totalBreaches sums all failures")
        void totalBreachesSumsAll() {
            SlaResult r = new SlaResult(1, 2, 3, 10);
            assertEquals(6, r.totalBreaches());
        }

        @Test
        @DisplayName("verdict returns FAIL when breaches exist")
        void verdictFailOnBreaches() {
            assertEquals("FAIL", new SlaResult(1, 0, 0, 5).verdict());
        }

        @Test
        @DisplayName("verdict returns PASS when no breaches")
        void verdictPassOnZeroBreaches() {
            assertEquals("PASS", new SlaResult(0, 0, 0, 5).verdict());
        }

        @Test
        @DisplayName("totalBreaches is zero when all zeros")
        void totalBreachesZero() {
            assertEquals(0, new SlaResult(0, 0, 0, 3).totalBreaches());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // evaluate()
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("evaluate()")
    class EvaluateTests {

        @Test
        @DisplayName("all thresholds disabled (-1) → zero breaches")
        void allDisabled() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "5.00%", "10.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, -1, -1, -1, false);
            assertEquals(0, r.totalBreaches());
            assertEquals("PASS", r.verdict());
        }

        @Test
        @DisplayName("TPS below threshold → tpsFails incremented")
        void tpsBreach() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "1.00%", "5.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, 10.0, -1, -1, false);
            assertEquals(1, r.tpsFails());
            assertEquals(0, r.errorFails());
        }

        @Test
        @DisplayName("TPS above threshold → no breach")
        void tpsPass() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "1.00%", "15.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, 10.0, -1, -1, false);
            assertEquals(0, r.tpsFails());
        }

        @Test
        @DisplayName("error rate above threshold → errorFails incremented")
        void errorBreach() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "8.00%", "10.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, -1, 5.0, -1, false);
            assertEquals(1, r.errorFails());
        }

        @Test
        @DisplayName("error rate within threshold → no breach")
        void errorPass() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "3.00%", "10.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, -1, 5.0, -1, false);
            assertEquals(0, r.errorFails());
        }

        @Test
        @DisplayName("RT (Pnn) above threshold → rtFails incremented")
        void rtPnnBreach() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "3000", "1.00%", "10.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, -1, -1, 2000, false);
            assertEquals(1, r.rtFails());
        }

        @Test
        @DisplayName("RT (Avg) above threshold → rtFails incremented")
        void rtAvgBreach() {
            List<String[]> rows = Collections.singletonList(row("Login", "2500", "300", "1.00%", "10.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, -1, -1, 2000, true);
            assertEquals(1, r.rtFails());
        }

        @Test
        @DisplayName("RT within threshold → no breach")
        void rtPass() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "1500", "1.00%", "10.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, -1, -1, 2000, false);
            assertEquals(0, r.rtFails());
        }

        @Test
        @DisplayName("multiple rows — mixed results")
        void multipleRowsMixed() {
            List<String[]> rows = Arrays.asList(
                    row("Login", "200", "1500", "2.00%", "15.0/sec"),
                    row("Checkout", "200", "3000", "8.00%", "5.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, 10.0, 5.0, 2000, false);
            assertEquals(1, r.tpsFails());    // Checkout TPS 5 < 10
            assertEquals(1, r.errorFails());  // Checkout error 8% > 5%
            assertEquals(1, r.rtFails());     // Checkout p90 3000 > 2000
            assertEquals(2, r.totalRows());
        }

        @Test
        @DisplayName("empty row list → zero breaches")
        void emptyRows() {
            SlaResult r = SlaEvaluator.evaluate(List.of(), 10.0, 5.0, 2000, false);
            assertEquals(0, r.totalBreaches());
            assertEquals(0, r.totalRows());
        }

        @Test
        @DisplayName("all three thresholds breached on same row")
        void allThreeBreached() {
            List<String[]> rows = Collections.singletonList(row("Bad", "3000", "5000", "10.00%", "2.0/sec"));
            SlaResult r = SlaEvaluator.evaluate(rows, 5.0, 5.0, 2000, false);
            assertEquals(1, r.tpsFails());
            assertEquals(1, r.errorFails());
            assertEquals(1, r.rtFails());
            assertEquals("FAIL", r.verdict());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildVerdictHtml()
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildVerdictHtml()")
    class VerdictHtmlTests {

        @Test
        @DisplayName("PASS verdict contains sla-pass badge")
        void passVerdictBadge() {
            SlaResult r = new SlaResult(0, 0, 0, 2);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.0, 5.0, 2000, false, 90);
            assertTrue(html.contains("sla-pass"));
            assertTrue(html.contains("PASS"));
        }

        @Test
        @DisplayName("FAIL verdict contains sla-fail badge")
        void failVerdictBadge() {
            SlaResult r = new SlaResult(1, 0, 0, 2);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.0, -1, -1, false, 90);
            assertTrue(html.contains("sla-fail"));
            assertTrue(html.contains("FAIL"));
        }

        @Test
        @DisplayName("TPS threshold row rendered when configured")
        void tpsRowRendered() {
            SlaResult r = new SlaResult(1, 0, 0, 2);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.0, -1, -1, false, 90);
            assertTrue(html.contains("TPS"));
            assertTrue(html.contains("1/2"));
        }

        @Test
        @DisplayName("error threshold row rendered when configured")
        void errorRowRendered() {
            SlaResult r = new SlaResult(0, 2, 0, 3);
            String html = SlaEvaluator.buildVerdictHtml(r, -1, 5.0, -1, false, 90);
            assertTrue(html.contains("Error%"));
            assertTrue(html.contains("2/3"));
        }

        @Test
        @DisplayName("RT threshold row with Pnn label")
        void rtPnnLabel() {
            SlaResult r = new SlaResult(0, 0, 1, 2);
            String html = SlaEvaluator.buildVerdictHtml(r, -1, -1, 2000, false, 90);
            assertTrue(html.contains("P90 RT"));
        }

        @Test
        @DisplayName("RT threshold row with Avg label")
        void rtAvgLabel() {
            SlaResult r = new SlaResult(0, 0, 1, 2);
            String html = SlaEvaluator.buildVerdictHtml(r, -1, -1, 2000, true, 90);
            assertTrue(html.contains("Avg RT"));
        }

        @Test
        @DisplayName("no SLA configured shows 'No SLA thresholds' message")
        void noSlaMessage() {
            SlaResult r = new SlaResult(0, 0, 0, 2);
            String html = SlaEvaluator.buildVerdictHtml(r, -1, -1, -1, false, 90);
            assertTrue(html.contains("No SLA thresholds configured"));
        }

        @Test
        @DisplayName("breach count summary with singular")
        void breachSingular() {
            SlaResult r = new SlaResult(1, 0, 0, 1);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.0, -1, -1, false, 90);
            assertTrue(html.contains("1 breach"));
            assertTrue(html.contains("1 transaction"));
        }

        @Test
        @DisplayName("breach count summary with plural")
        void breachPlural() {
            SlaResult r = new SlaResult(2, 1, 0, 3);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.0, 5.0, -1, false, 90);
            assertTrue(html.contains("3 breaches"));
            assertTrue(html.contains("3 transactions"));
        }

        @Test
        @DisplayName("integer threshold formatted without decimal")
        void integerThreshold() {
            SlaResult r = new SlaResult(0, 0, 0, 1);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.0, 5.0, -1, false, 90);
            assertTrue(html.contains("10") && !html.contains("10.0"));
        }

        @Test
        @DisplayName("fractional threshold formatted with one decimal")
        void fractionalThreshold() {
            SlaResult r = new SlaResult(0, 0, 0, 1);
            String html = SlaEvaluator.buildVerdictHtml(r, 10.5, -1, -1, false, 90);
            assertTrue(html.contains("10.5"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildClassificationVerdictHtml()
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildClassificationVerdictHtml()")
    class ClassificationHtmlTests {

        @Test
        @DisplayName("PASS verdict with THROUGHPUT-BOUND")
        void passThroughputBound() {
            String html = SlaEvaluator.buildClassificationVerdictHtml(
                    "PASS", "THROUGHPUT-BOUND", "Healthy plateau");
            assertTrue(html.contains("sla-pass"));
            assertTrue(html.contains("PASS"));
            assertTrue(html.contains("THROUGHPUT-BOUND"));
            assertTrue(html.contains("Healthy plateau"));
            assertTrue(html.contains("CLASSIFICATION"));
        }

        @Test
        @DisplayName("FAIL verdict with ERROR-BOUND")
        void failErrorBound() {
            String html = SlaEvaluator.buildClassificationVerdictHtml(
                    "FAIL", "ERROR-BOUND", "Error rate > 2%");
            assertTrue(html.contains("sla-fail"));
            assertTrue(html.contains("FAIL"));
            assertTrue(html.contains("ERROR-BOUND"));
        }

        @Test
        @DisplayName("HTML escapes special characters in reasoning")
        void htmlEscapesSpecialChars() {
            String html = SlaEvaluator.buildClassificationVerdictHtml(
                    "PASS", "TEST", "ratio < 3 & value > 5");
            assertTrue(html.contains("&lt;"));
            assertTrue(html.contains("&amp;"));
            assertTrue(html.contains("&gt;"));
        }

        @Test
        @DisplayName("null reasoning renders empty")
        void nullReasoningRendersEmpty() {
            String html = SlaEvaluator.buildClassificationVerdictHtml("PASS", "TEST", null);
            assertTrue(html.contains("<td></td>"));
        }

        @Test
        @DisplayName("contains 'No SLA thresholds configured' footer")
        void containsFooter() {
            String html = SlaEvaluator.buildClassificationVerdictHtml("PASS", "TEST", "reason");
            assertTrue(html.contains("No SLA thresholds configured"));
            assertTrue(html.contains("workload classification"));
        }
    }
}
