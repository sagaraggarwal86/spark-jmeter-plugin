package com.personal.jmeter.ai.prompt;

import com.personal.jmeter.parser.JTLParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for package-private static methods in {@link PromptBuilder}:
 * {@link PromptBuilder#buildClassificationSummary} and
 * {@link PromptBuilder#buildOverallVerdictSummary}.
 *
 * <p>These methods implement the bottleneck classification and final PASS/FAIL
 * verdict logic. Testing them directly — without going through the full
 * {@code build()} pipeline — isolates the decision-tree logic from prompt
 * assembly concerns.</p>
 *
 * <p>No file system, no network, no Swing — pure in-memory verification.</p>
 */
@DisplayName("PromptBuilder — static analysis methods")
class PromptBuilderStaticsTest {

    // ─────────────────────────────────────────────────────────────
    // buildClassificationSummary
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildClassificationSummary")
    class ClassificationSummaryTests {

        @Test
        @DisplayName("null globalStats → THROUGHPUT-BOUND default")
        void nullGlobalStatsReturnsDefault() {
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(null, null);
            assertEquals("THROUGHPUT-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("empty globalStats → THROUGHPUT-BOUND default")
        void emptyGlobalStatsReturnsDefault() {
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(
                    Collections.emptyMap(), null);
            assertEquals("THROUGHPUT-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("errorRatePct > 2.0 → ERROR-BOUND")
        void highErrorRateIsErrorBound() {
            Map<String, Object> stats = globalStats(500.0, 2000.0, 3.5);
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, null);
            assertEquals("ERROR-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("errorRatePct == 2.0 → not ERROR-BOUND (boundary)")
        void exactlyTwoPercentNotErrorBound() {
            Map<String, Object> stats = globalStats(500.0, 2000.0, 2.0);
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, null);
            assertNotEquals("ERROR-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("plateaued + latencyRatio > 3.0 → CAPACITY-WALL")
        void plateauedHighLatencyIsCapacityWall() {
            Map<String, Object> stats = globalStats(200.0, 800.0, 0.5); // ratio = 4.0
            List<JTLParser.TimeBucket> buckets = plateauedBuckets();
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, buckets);
            assertEquals("CAPACITY-WALL", result.get("label"));
        }

        @Test
        @DisplayName("not plateaued + latencyRatio > 3.0 + errPct <= 2.0 → LATENCY-BOUND")
        void notPlateauedHighLatencyIsLatencyBound() {
            Map<String, Object> stats = globalStats(200.0, 800.0, 0.5); // ratio = 4.0
            List<JTLParser.TimeBucket> buckets = risingBuckets();
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, buckets);
            assertEquals("LATENCY-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("plateaued + latencyRatio <= 3.0 + errPct <= 2.0 → THROUGHPUT-BOUND")
        void plateauedLowLatencyIsThroughputBound() {
            Map<String, Object> stats = globalStats(200.0, 400.0, 0.5); // ratio = 2.0
            List<JTLParser.TimeBucket> buckets = plateauedBuckets();
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, buckets);
            assertEquals("THROUGHPUT-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("no conditions met → THROUGHPUT-BOUND default")
        void noConditionsMetDefaultsThroughputBound() {
            Map<String, Object> stats = globalStats(200.0, 300.0, 0.5); // ratio = 1.5, no plateau
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, risingBuckets());
            assertEquals("THROUGHPUT-BOUND", result.get("label"));
        }

        @Test
        @DisplayName("avgResponseMs == 0 → latencyRatio is 0, reasoning note present")
        void zeroAvgResponseMsHandledSafely() {
            Map<String, Object> stats = globalStats(0.0, 1000.0, 0.5);
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, null);
            assertNotNull(result.get("label"));
            assertTrue(result.containsKey("latencyRatioNote"),
                    "Should contain latencyRatioNote when avgMs is 0");
        }

        @Test
        @DisplayName("result always contains 'label' and 'reasoning' keys")
        void resultAlwaysContainsRequiredKeys() {
            Map<String, Object> stats = globalStats(300.0, 900.0, 1.0);
            Map<String, Object> result = PromptBuilder.buildClassificationSummary(stats, null);
            assertTrue(result.containsKey("label"));
            assertTrue(result.containsKey("reasoning"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildOverallVerdictSummary
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildOverallVerdictSummary")
    class OverallVerdictSummaryTests {

        @Test
        @DisplayName("error SLA BREACH → FAIL with source=SLA")
        void errorSlaBreachIsFailSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("BREACH"), slaSummary("WITHIN"),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("RT SLA BREACH → FAIL with source=SLA")
        void rtSlaBreachIsFailSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("WITHIN"), slaSummary("BREACH"),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("both SLAs WITHIN → PASS with source=SLA")
        void bothWithinIsPassSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("WITHIN"), slaSummary("WITHIN"),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("PASS", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("no SLA + CAPACITY-WALL → FAIL with source=CLASSIFICATION")
        void capacityWallNoSlaIsFail() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(),
                    classification("CAPACITY-WALL"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("no SLA + ERROR-BOUND → FAIL with source=CLASSIFICATION")
        void errorBoundNoSlaIsFail() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(),
                    classification("ERROR-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("no SLA + LATENCY-BOUND + p99 > 5x avg → FAIL")
        void latencyBoundHighP99IsFail() {
            Map<String, Object> stats = globalStats(100.0, 600.0, 0.5); // p99=600 > 5*100
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(),
                    classification("LATENCY-BOUND"), stats);
            assertEquals("FAIL", result.get("verdict"));
        }

        @Test
        @DisplayName("no SLA + LATENCY-BOUND + p99 <= 5x avg → PASS")
        void latencyBoundLowP99IsPass() {
            Map<String, Object> stats = globalStats(100.0, 400.0, 0.5); // p99=400 <= 5*100
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(),
                    classification("LATENCY-BOUND"), stats);
            assertEquals("PASS", result.get("verdict"));
        }

        @Test
        @DisplayName("no SLA + THROUGHPUT-BOUND → PASS with source=CLASSIFICATION")
        void throughputBoundNoSlaIsPass() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("PASS", result.get("verdict"));
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("both SLAs NOT_CONFIGURED (no SLA at all) → CLASSIFICATION source")
        void bothNotConfiguredUsesClassification() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("null inputs handled without NullPointerException")
        void nullInputsHandledSafely() {
            assertDoesNotThrow(() ->
                    PromptBuilder.buildOverallVerdictSummary(null, null, null, emptyGlobalStats()));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static Map<String, Object> globalStats(double avgMs, double p99Ms, double errPct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgResponseMs", avgMs);
        m.put("p99ResponseMs", p99Ms);
        m.put("errorRatePct", errPct);
        return m;
    }

    private static Map<String, Object> emptyGlobalStats() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> slaSummary(String verdict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verdict", verdict);
        m.put("configured", true);
        return m;
    }

    private static Map<String, Object> noSlaSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verdict", "NOT_CONFIGURED");
        m.put("configured", false);
        return m;
    }

    private static Map<String, Object> classification(String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        return m;
    }

    /**
     * Produces a plateaued TPS series: tail avg ≥ 90% of peak.
     * 8 buckets all at TPS=10.0 — tail avg == peak → plateauRatio=1.0.
     */
    private static List<JTLParser.TimeBucket> plateauedBuckets() {
        long base = 1_700_000_000_000L;
        long interval = 30_000L;
        List<JTLParser.TimeBucket> list = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            list.add(new JTLParser.TimeBucket(base + i * interval, 200.0, 0.5, 10.0, 50.0));
        }
        return list;
    }

    /**
     * Produces a rising TPS series: tail avg < 90% of peak (not plateaued).
     * Peak is 10.0 at bucket 0; tail buckets are at 5.0 → plateauRatio=0.5.
     */
    private static List<JTLParser.TimeBucket> risingBuckets() {
        long base = 1_700_000_000_000L;
        long interval = 30_000L;
        List<JTLParser.TimeBucket> list = new java.util.ArrayList<>();
        list.add(new JTLParser.TimeBucket(base, 200.0, 0.5, 10.0, 50.0));
        for (int i = 1; i < 8; i++) {
            list.add(new JTLParser.TimeBucket(base + i * interval, 200.0, 0.5, 5.0, 25.0));
        }
        return list;
    }
}
