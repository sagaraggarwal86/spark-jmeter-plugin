package io.github.sagaraggarwal86.jmeter.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DataReportBuilder}.
 *
 * <p>Pure in-memory — no Swing, no file system, no network.</p>
 */
@DisplayName("DataReportBuilder")
class DataReportBuilderTest {

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static Map<String, Object> classification(String label, String reasoning) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("reasoning", reasoning);
        return m;
    }

    private static Map<String, Object> globalStats(double avgMs, double p99Ms, double errPct,
                                                   long totalReqs, double tps) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgResponseMs", avgMs);
        m.put("p99ResponseMs", p99Ms);
        m.put("errorRatePct", errPct);
        m.put("totalRequests", totalReqs);
        m.put("throughputTPS", tps);
        return m;
    }

    /**
     * Row layout matches ColumnIndex: [0]=name, [1]=count, ..., [4]=avg, [7]=pnn, [9]=errRate, [10]=tps
     */
    private static String[] row(String name, String avg, String pnn, String errRate, String tps) {
        return new String[]{name, "100", "95", "5", avg, "10", "500", pnn, "50.0", errRate, tps, "5.00", "512"};
    }

    // ─────────────────────────────────────────────────────────────
    // buildSections
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSections()")
    class BuildSectionsTests {

        @Test
        @DisplayName("minimal — empty when no classification, SLA, or rows")
        void minimalSections() {
            List<String[]> sections = DataReportBuilder.buildSections(
                    null, null, null, Collections.emptyList(), 90, "pnn");
            assertTrue(sections.isEmpty());
        }

        @Test
        @DisplayName("classification section present when classification map provided")
        void classificationPresent() {
            List<String[]> sections = DataReportBuilder.buildSections(
                    classification("THROUGHPUT-BOUND", "Healthy"),
                    globalStats(200, 400, 0.5, 10000, 50.0),
                    null, Collections.emptyList(), 90, "pnn");
            assertTrue(sections.stream().anyMatch(s -> s[0].equals("Workload Classification")));
        }

        @Test
        @DisplayName("SLA section present when slaVerdictHtml provided")
        void slaPresent() {
            List<String[]> sections = DataReportBuilder.buildSections(
                    null, null,
                    "<div>SLA PASS</div>",
                    Collections.emptyList(), 90, "pnn");
            assertTrue(sections.stream().anyMatch(s -> s[0].equals("SLA Evaluation")));
        }

        @Test
        @DisplayName("blank SLA html excluded")
        void blankSlaExcluded() {
            List<String[]> sections = DataReportBuilder.buildSections(
                    null, null, "   ", Collections.emptyList(), 90, "pnn");
            assertFalse(sections.stream().anyMatch(s -> s[0].equals("SLA Evaluation")));
        }

        @Test
        @DisplayName("slowest endpoints present when rows provided")
        void slowestPresent() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "1.00%", "10.0/sec"));
            List<String[]> sections = DataReportBuilder.buildSections(
                    null, null, null, rows, 90, "pnn");
            assertTrue(sections.stream().anyMatch(s -> s[0].equals("Slowest Endpoints")));
        }

        @Test
        @DisplayName("all three sections present with full data")
        void allThreePresent() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "1.00%", "10.0/sec"));
            List<String[]> sections = DataReportBuilder.buildSections(
                    classification("ERROR-BOUND", "High errors"),
                    globalStats(200, 400, 3.5, 10000, 50.0),
                    "<div>SLA FAIL</div>",
                    rows, 90, "pnn");
            assertEquals(3, sections.size());
            assertEquals("Workload Classification", sections.get(0)[0]);
            assertEquals("SLA Evaluation", sections.get(1)[0]);
            assertEquals("Slowest Endpoints", sections.get(2)[0]);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildClassificationSection
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildClassificationSection()")
    class ClassificationTests {

        @Test
        @DisplayName("THROUGHPUT-BOUND shows badge-pass")
        void throughputBound() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("THROUGHPUT-BOUND", "Healthy workload"),
                    globalStats(200, 400, 0.5, 10000, 50.0));
            assertTrue(html.contains("badge-pass"));
            assertTrue(html.contains("THROUGHPUT-BOUND"));
        }

        @Test
        @DisplayName("ERROR-BOUND shows badge-fail")
        void errorBound() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("ERROR-BOUND", "High error rate"),
                    globalStats(200, 400, 3.5, 10000, 50.0));
            assertTrue(html.contains("badge-fail"));
        }

        @Test
        @DisplayName("CAPACITY-WALL shows badge-fail")
        void capacityWall() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("CAPACITY-WALL", "TPS plateaued"), null);
            assertTrue(html.contains("badge-fail"));
        }

        @Test
        @DisplayName("LATENCY-BOUND shows badge-warn")
        void latencyBound() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("LATENCY-BOUND", "High p99"), null);
            assertTrue(html.contains("badge-warn"));
        }

        @Test
        @DisplayName("human-readable reasoning for THROUGHPUT-BOUND")
        void reasoningThroughputBound() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("THROUGHPUT-BOUND", "raw ignored"),
                    null);
            assertTrue(html.contains("Healthy workload"));
            assertTrue(html.contains("Analysis"));
        }

        @Test
        @DisplayName("human-readable reasoning for ERROR-BOUND")
        void reasoningErrorBound() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("ERROR-BOUND", "raw ignored"),
                    null);
            assertTrue(html.contains("Error-bound workload"));
            assertTrue(html.contains("2% threshold"));
        }

        @Test
        @DisplayName("human-readable reasoning for CAPACITY-WALL")
        void reasoningCapacityWall() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("CAPACITY-WALL", "raw ignored"),
                    null);
            assertTrue(html.contains("Capacity wall detected"));
        }

        @Test
        @DisplayName("human-readable reasoning for LATENCY-BOUND")
        void reasoningLatencyBound() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("LATENCY-BOUND", "raw ignored"),
                    null);
            assertTrue(html.contains("Latency-bound workload"));
        }

        @Test
        @DisplayName("globalStats rendered as key metrics table")
        void globalStatsRendered() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("THROUGHPUT-BOUND", "OK"),
                    globalStats(200.5, 400, 0.5, 10000, 50.0));
            assertTrue(html.contains("Avg Response Time"));
            assertTrue(html.contains("200.50 ms"));
            assertTrue(html.contains("P99 Response Time"));
            assertTrue(html.contains("400 ms")); // integer formatted
            assertTrue(html.contains("50/sec"));
        }

        @Test
        @DisplayName("null globalStats — no metrics table")
        void nullGlobalStats() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("THROUGHPUT-BOUND", "OK"), null);
            assertFalse(html.contains("Key Metrics"));
        }

        @Test
        @DisplayName("empty globalStats — no metrics table")
        void emptyGlobalStats() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("THROUGHPUT-BOUND", "OK"), Collections.emptyMap());
            assertFalse(html.contains("Key Metrics"));
        }

        @Test
        @DisplayName("HTML escapes special characters in label")
        void htmlEscapesSpecialChars() {
            String html = DataReportBuilder.buildClassificationSection(
                    classification("TEST<L&B>", "ignored"), null);
            assertTrue(html.contains("&lt;"));
            assertTrue(html.contains("&amp;"));
            assertTrue(html.contains("&gt;"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildSlowestEndpointsSection
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSlowestEndpointsSection()")
    class SlowestEndpointsTests {

        @Test
        @DisplayName("top 5 sorted by Pnn RT descending")
        void top5ByPnn() {
            List<String[]> rows = Arrays.asList(
                    row("A", "100", "100", "1%", "10/sec"),
                    row("B", "200", "500", "1%", "10/sec"),
                    row("C", "300", "300", "1%", "10/sec"),
                    row("D", "400", "200", "1%", "10/sec"),
                    row("E", "500", "400", "1%", "10/sec"),
                    row("F", "600", "600", "1%", "10/sec"));
            String html = DataReportBuilder.buildSlowestEndpointsSection(rows, 90, "pnn");
            // F(600) > B(500) > E(400) > C(300) > D(200) — A(100) excluded
            assertTrue(html.contains("F"));
            assertTrue(html.contains("B"));
            assertFalse(html.contains(">A<")); // A should not appear (6th)
        }

        @Test
        @DisplayName("uses Avg RT when rtMetric is avg")
        void usesAvg() {
            List<String[]> rows = Arrays.asList(
                    row("Slow", "999", "100", "1%", "10/sec"),
                    row("Fast", "50", "900", "1%", "10/sec"));
            String html = DataReportBuilder.buildSlowestEndpointsSection(rows, 90, "avg");
            assertTrue(html.contains("Avg RT (ms)"));
            // Slow (avg=999) should be first
            int slowIdx = html.indexOf("Slow");
            int fastIdx = html.indexOf("Fast");
            assertTrue(slowIdx < fastIdx);
        }

        @Test
        @DisplayName("uses Pnn RT label with percentile")
        void pnnLabel() {
            List<String[]> rows = Collections.singletonList(row("A", "100", "200", "1%", "10/sec"));
            String html = DataReportBuilder.buildSlowestEndpointsSection(rows, 95, "pnn");
            assertTrue(html.contains("P95 RT (ms)"));
        }

        @Test
        @DisplayName("has h2 heading and table structure")
        void hasStructure() {
            List<String[]> rows = Collections.singletonList(row("Login", "200", "300", "1%", "10/sec"));
            String html = DataReportBuilder.buildSlowestEndpointsSection(rows, 90, "pnn");
            assertTrue(html.contains("<h2>Slowest Endpoints</h2>"));
            assertTrue(html.contains("<table"));
            assertTrue(html.contains("Login"));
        }

        @Test
        @DisplayName("fewer than 5 rows renders all")
        void fewerThan5() {
            List<String[]> rows = Arrays.asList(
                    row("A", "100", "100", "1%", "10/sec"),
                    row("B", "200", "200", "1%", "10/sec"));
            String html = DataReportBuilder.buildSlowestEndpointsSection(rows, 90, "pnn");
            assertTrue(html.contains("A"));
            assertTrue(html.contains("B"));
        }

        @Test
        @DisplayName("renders rank numbers")
        void rendersRankNumbers() {
            List<String[]> rows = Arrays.asList(
                    row("A", "100", "300", "1%", "10/sec"),
                    row("B", "200", "200", "1%", "10/sec"));
            String html = DataReportBuilder.buildSlowestEndpointsSection(rows, 90, "pnn");
            assertTrue(html.contains(">1<"));
            assertTrue(html.contains(">2<"));
        }
    }
}
