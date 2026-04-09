package io.github.sagaraggarwal86.jmeter.ai.prompt;

import io.github.sagaraggarwal86.jmeter.parser.JTLParser;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
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

    @BeforeAll
    static void initJMeter() {
        System.setProperty("java.awt.headless", "true");
        URL propsUrl = PromptBuilderStaticsTest.class.getClassLoader().getResource("jmeter.properties");
        if (propsUrl != null) {
            JMeterUtils.loadJMeterProperties(propsUrl.getFile());
            JMeterUtils.initLocale();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildClassificationSummary
    // ─────────────────────────────────────────────────────────────

    private static Map<String, Object> globalStats(double avgMs, double p99Ms, double errPct) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgResponseMs", avgMs);
        m.put("p99ResponseMs", p99Ms);
        m.put("errorRatePct", errPct);
        return m;
    }

    // ─────────────────────────────────────────────────────────────
    // buildOverallVerdictSummary
    // ─────────────────────────────────────────────────────────────

    private static Map<String, Object> emptyGlobalStats() {
        return new LinkedHashMap<>();
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

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

    @Nested
    @DisplayName("buildOverallVerdictSummary")
    class OverallVerdictSummaryTests {

        @Test
        @DisplayName("error SLA BREACH → FAIL with source=SLA")
        void errorSlaBreachIsFailSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("BREACH"), slaSummary("WITHIN"), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("RT SLA BREACH → FAIL with source=SLA")
        void rtSlaBreachIsFailSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("WITHIN"), slaSummary("BREACH"), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("both SLAs WITHIN → PASS with source=SLA")
        void bothWithinIsPassSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("WITHIN"), slaSummary("WITHIN"), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("PASS", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("no SLA + CAPACITY-WALL → FAIL with source=CLASSIFICATION")
        void capacityWallNoSlaIsFail() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("CAPACITY-WALL"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("no SLA + ERROR-BOUND → FAIL with source=CLASSIFICATION")
        void errorBoundNoSlaIsFail() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("ERROR-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("no SLA + LATENCY-BOUND + p99 > 5x avg → FAIL")
        void latencyBoundHighP99IsFail() {
            Map<String, Object> stats = globalStats(100.0, 600.0, 0.5); // p99=600 > 5*100
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("LATENCY-BOUND"), stats);
            assertEquals("FAIL", result.get("verdict"));
        }

        @Test
        @DisplayName("no SLA + LATENCY-BOUND + p99 <= 5x avg → PASS")
        void latencyBoundLowP99IsPass() {
            Map<String, Object> stats = globalStats(100.0, 400.0, 0.5); // p99=400 <= 5*100
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("LATENCY-BOUND"), stats);
            assertEquals("PASS", result.get("verdict"));
        }

        @Test
        @DisplayName("no SLA + THROUGHPUT-BOUND → PASS with source=CLASSIFICATION")
        void throughputBoundNoSlaIsPass() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("PASS", result.get("verdict"));
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("both SLAs NOT_CONFIGURED (no SLA at all) → CLASSIFICATION source")
        void bothNotConfiguredUsesClassification() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("CLASSIFICATION", result.get("source"));
        }

        @Test
        @DisplayName("null inputs handled without NullPointerException")
        void nullInputsHandledSafely() {
            assertDoesNotThrow(() ->
                    PromptBuilder.buildOverallVerdictSummary(null, null, null, null, emptyGlobalStats()));
        }

        @Test
        @DisplayName("both SLAs BREACH → reasoning mentions both")
        void bothBreachReasoningMentionsBoth() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("BREACH"), slaSummary("BREACH"), noSlaSummary(),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            String reasoning = String.valueOf(result.get("reasoning"));
            assertTrue(reasoning.contains("errorSlaSummary"));
            assertTrue(reasoning.contains("rtSlaSummary"));
        }

        @Test
        @DisplayName("no SLA + LATENCY-BOUND + avgMs==0 → PASS (p99 cannot exceed 5*0)")
        void latencyBoundZeroAvgIsPass() {
            Map<String, Object> stats = globalStats(0.0, 600.0, 0.5);
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), noSlaSummary(),
                    classification("LATENCY-BOUND"), stats);
            assertEquals("PASS", result.get("verdict"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildGlobalStats — basic validation
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildGlobalStats")
    class GlobalStatsTests {

        @Test
        @DisplayName("null TOTAL label → returns empty map")
        void nullTotalReturnsEmpty() {
            Map<String, org.apache.jmeter.visualizers.SamplingStatCalculator> results = new LinkedHashMap<>();
            Map<String, Object> stats = PromptBuilder.buildGlobalStats(results, 90, 0.90,
                    PromptBuilder.LatencyContext.ABSENT);
            assertTrue(stats.isEmpty());
        }

        @Test
        @DisplayName("TOTAL with zero count → returns empty map")
        void zeroCountTotalReturnsEmpty() {
            Map<String, org.apache.jmeter.visualizers.SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("TOTAL", new org.apache.jmeter.visualizers.SamplingStatCalculator("TOTAL"));
            Map<String, Object> stats = PromptBuilder.buildGlobalStats(results, 90, 0.90,
                    PromptBuilder.LatencyContext.ABSENT);
            assertTrue(stats.isEmpty());
        }

        @Test
        @DisplayName("TOTAL with samples → contains expected keys")
        void totalWithSamplesContainsKeys() {
            org.apache.jmeter.visualizers.SamplingStatCalculator calc =
                    new org.apache.jmeter.visualizers.SamplingStatCalculator("TOTAL");
            org.apache.jmeter.samplers.SampleResult sr = new org.apache.jmeter.samplers.SampleResult();
            sr.setStampAndTime(System.currentTimeMillis(), 200L);
            sr.setSuccessful(true);
            calc.addSample(sr);

            Map<String, org.apache.jmeter.visualizers.SamplingStatCalculator> results = new LinkedHashMap<>();
            results.put("TOTAL", calc);

            Map<String, Object> stats = PromptBuilder.buildGlobalStats(results, 90, 0.90,
                    PromptBuilder.LatencyContext.ABSENT);
            assertFalse(stats.isEmpty());
            assertTrue(stats.containsKey("avgResponseMs"));
            assertTrue(stats.containsKey("p99ResponseMs"));
            assertTrue(stats.containsKey("errorRatePct"));
            assertTrue(stats.containsKey("totalRequests"));
            assertTrue(stats.containsKey("throughputTPS"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // parseTpsSlaThreshold
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("parseTpsSlaThreshold")
    class ParseTpsSlaThresholdTests {

        @Test
        @DisplayName("null → -1")
        void nullReturnsNegative() {
            assertEquals(-1.0, PromptBuilder.parseTpsSlaThreshold(null));
        }

        @Test
        @DisplayName("blank → -1")
        void blankReturnsNegative() {
            assertEquals(-1.0, PromptBuilder.parseTpsSlaThreshold("  "));
        }

        @Test
        @DisplayName("'Not configured' → -1")
        void notConfiguredReturnsNegative() {
            assertEquals(-1.0, PromptBuilder.parseTpsSlaThreshold("Not configured"));
        }

        @Test
        @DisplayName("plain number → parsed value")
        void plainNumber() {
            assertEquals(0.2, PromptBuilder.parseTpsSlaThreshold("0.2"), 0.001);
        }

        @Test
        @DisplayName("number with /sec suffix → parsed value")
        void withSecSuffix() {
            assertEquals(0.5, PromptBuilder.parseTpsSlaThreshold("0.5/sec"), 0.001);
        }

        @Test
        @DisplayName("number with /s suffix → parsed value")
        void withShortSuffix() {
            assertEquals(1.0, PromptBuilder.parseTpsSlaThreshold("1.0/s"), 0.001);
        }

        @Test
        @DisplayName("zero → -1 (disabled)")
        void zeroReturnsNegative() {
            assertEquals(-1.0, PromptBuilder.parseTpsSlaThreshold("0"));
        }

        @Test
        @DisplayName("negative → -1 (disabled)")
        void negativeReturnsNegative() {
            assertEquals(-1.0, PromptBuilder.parseTpsSlaThreshold("-5"));
        }

        @Test
        @DisplayName("unparseable → -1")
        void unparseableReturnsNegative() {
            assertEquals(-1.0, PromptBuilder.parseTpsSlaThreshold("abc"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildOverallVerdictSummary — TPS SLA scenarios
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildOverallVerdictSummary — TPS SLA")
    class TpsSlaVerdictTests {

        @Test
        @DisplayName("TPS SLA BREACH alone → FAIL with source=SLA")
        void tpsSlaBreachIsFailSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), slaSummary("BREACH"),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
            assertTrue(String.valueOf(result.get("reasoning")).contains("tpsSlaSummary"));
        }

        @Test
        @DisplayName("TPS SLA WITHIN + others not configured → PASS")
        void tpsSlaWithinIsPassSla() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    noSlaSummary(), noSlaSummary(), slaSummary("WITHIN"),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("PASS", result.get("verdict"));
            assertEquals("SLA", result.get("source"));
        }

        @Test
        @DisplayName("all three SLAs BREACH → reasoning mentions all three")
        void allThreeBreachReasoningMentionsAll() {
            Map<String, Object> result = PromptBuilder.buildOverallVerdictSummary(
                    slaSummary("BREACH"), slaSummary("BREACH"), slaSummary("BREACH"),
                    classification("THROUGHPUT-BOUND"), emptyGlobalStats());
            assertEquals("FAIL", result.get("verdict"));
            String reasoning = String.valueOf(result.get("reasoning"));
            assertTrue(reasoning.contains("errorSlaSummary"));
            assertTrue(reasoning.contains("rtSlaSummary"));
            assertTrue(reasoning.contains("tpsSlaSummary"));
        }
    }
}
