package com.personal.jmeter.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Builds the two-part AI analysis prompt from already-aggregated JMeter results.
 *
 * <p><b>Standard 21 PromptBuilder contract:</b> {@code build} returns a
 * {@code PromptContent} containing a static {@code role:"system"} message
 * (the analytical framework with Layers 1–5 and eight mandatory report sections)
 * and a dynamic {@code role:"user"} message containing runtime test data with
 * all Standard 21 placeholders substituted.</p>
 *
 * <h3>JSON sections sent in the user message</h3>
 * <ul>
 *   <li>{@code globalStats}         — TOTAL-row KPIs</li>
 *   <li>{@code anomalyTransactions} — transactions breaching at least one threshold</li>
 *   <li>{@code errorEndpoints}      — transactions with any errors</li>
 *   <li>{@code slowestEndpoints}    — top-5 by Nth-percentile (label + value only)</li>
 * </ul>
 */
public class PromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String TOTAL_LABEL = "TOTAL";
    private static final double MEDIAN = 0.50;
    private static final int SLOWEST_TOP_N = 5;
    private static final String KEY_ERROR_RATE_PCT = "errorRatePct";

    // ── Anomaly thresholds ───────────────────────────────────────
    private static final double THRESHOLD_AVG_MS = 2_000.0;
    private static final double THRESHOLD_PCT_MS = 3_000.0;
    private static final double THRESHOLD_ERROR_PCT = 1.0;
    private static final double THRESHOLD_STD_DEV_RATIO = 0.5;

    // ─────────────────────────────────────────────────────────────
    // Latency context record
    // ─────────────────────────────────────────────────────────────

    /**
     * Carries the three Latency/Connect fields derived during JTL parsing,
     * and needed by the AI prompt's Advanced Web Diagnostics section.
     *
     * <p>Bundled as a record to keep {@link #build}'s parameter count bounded
     * and to give the three values a named, cohesive type.</p>
     *
     * @param avgLatencyMs   average Latency (TTFB) in ms across all samples;
     *                       0 when {@code latencyPresent} is false
     * @param avgConnectMs   average Connect time in ms across all samples;
     *                       0 when {@code latencyPresent} is false
     * @param latencyPresent true iff at least one sample had a non-zero Latency value;
     *                       false means the JTL has no usable Latency data and the
     *                       prompt must use inferred mode for timing decomposition
     */
    public record LatencyContext(long avgLatencyMs, long avgConnectMs, boolean latencyPresent) {
        /**
         * Sentinel for absent latency data — used when no JTL has been parsed
         * or all Latency values were zero.  Causes the prompt to enter inferred mode.
         */
        public static final LatencyContext ABSENT = new LatencyContext(0L, 0L, false);
    }

    /** Resolved system prompt — loaded from file or JAR resource. */
    private final String systemPrompt;

    /**
     * Production constructor: receives the system prompt from {@link PromptLoader}.
     *
     * @param systemPrompt non-blank system prompt text
     */
    public PromptBuilder(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank())
            throw new IllegalArgumentException("systemPrompt must not be blank");
        this.systemPrompt = systemPrompt;
    }

    /**
     * No-arg constructor for tests and backward compatibility.
     * Uses a minimal hardcoded stub prompt so tests have no classpath dependency.
     * Production code always uses {@link #PromptBuilder(String)} with a prompt
     * loaded by {@link com.personal.jmeter.ai.PromptLoader}.
     */
    PromptBuilder() {
        this("You are a performance engineer. Analyse the JMeter results and report findings.");
    }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * Safely extracts a {@code double} from a map value without unchecked casts.
     * Returns {@code 0.0} when the value is absent or not a {@link Number}.
     *
     * @param value map value to convert
     * @return numeric value, or 0.0
     */
    private static double asDouble(Object value) {
        return (value instanceof Number n) ? n.doubleValue() : 0.0;
    }

    /**
     * Builds the two-part AI analysis prompt from aggregated JMeter results.
     *
     * @param results           per-label aggregated statistics; must not be null
     * @param percentile        percentile to report (1–99)
     * @param request           scenario context (users, name, description, timing); must not be null
     * @param errorTypeSummary  top-5 failure types from the full JTL; empty list if no errors
     * @param latency           average Latency/Connect values and presence flag; use
     *                          {@link LatencyContext#ABSENT} when no latency data is available
     * @return {@code PromptContent} with system prompt and user message suitable for the AI API
     */
    public PromptContent build(Map<String, SamplingStatCalculator> results,
                               int percentile,
                               PromptRequest request,
                               List<Map<String, Object>> errorTypeSummary,
                               LatencyContext latency) {
        Objects.requireNonNull(results,  "results must not be null");
        Objects.requireNonNull(request,  "request must not be null");
        LatencyContext safeLatency = (latency != null) ? latency : LatencyContext.ABSENT;
        List<Map<String, Object>> safeErrorTypes =
                (errorTypeSummary != null) ? errorTypeSummary : Collections.emptyList();
        log.info("build: building prompt. labels={}, percentile={}, errorTypes={}, latencyPresent={}",
                results.size(), percentile, safeErrorTypes.size(), safeLatency.latencyPresent());

        String userMessage = buildUserMessage(results, percentile, request, safeErrorTypes, safeLatency);
        return new PromptContent(systemPrompt, userMessage);
    }

    // ─────────────────────────────────────────────────────────────
    // User message assembly (Standard 21 dynamic substitution)
    // ─────────────────────────────────────────────────────────────

    private String buildUserMessage(Map<String, SamplingStatCalculator> results,
                                    int percentile, PromptRequest request,
                                    List<Map<String, Object>> errorTypeSummary,
                                    LatencyContext latency) {
        String json = GSON.toJson(buildSummary(results, percentile, errorTypeSummary, latency));
        return """
                DATA SOURCE: All metrics below reflect the user's currently \
                configured view — time window, transaction scope, and \
                percentile have already been applied to the dataset. \
                Do not adjust, extrapolate, or question the scope. \
                Report on what is provided.

                Scenario              : %s
                Thread Group          : %s
                Description           : %s
                Users                 : %s
                Start Time            : %s
                End Time              : %s
                Duration              : %s
                Configured Percentile : P%d

                SLA Thresholds (user-configured):
                  Error Rate SLA      : %s
                  Response Time SLA   : %s on %s

                Global Statistics (JSON):
                %s""".formatted(
                orNotProvided(request.scenarioName()),
                orNotProvided(request.threadGroupName()),
                orNotProvided(request.scenarioDesc()),
                orNotProvided(request.users()),
                orNotProvided(request.startTime()),
                orNotProvided(request.endTime()),
                orNotProvided(request.duration()),
                request.configuredPercentile(),
                request.errorSlaThresholdPct(),
                request.rtSlaThresholdMs(),
                request.rtSlaMetric(),
                json);
    }

    private static String orNotProvided(String value) {
        return (value == null || value.isBlank()) ? "Not provided" : value.trim();
    }

    // ─────────────────────────────────────────────────────────────
    // JSON summary
    // ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildSummary(Map<String, SamplingStatCalculator> results,
                                             int percentile,
                                             List<Map<String, Object>> errorTypeSummary,
                                             LatencyContext latency) {
        final double pFraction = percentile / 100.0;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("globalStats",          buildGlobalStats(results, percentile, pFraction, latency));
        summary.put("anomalyTransactions",  buildAnomalyTransactions(results, percentile, pFraction));
        summary.put("errorEndpoints",       buildErrorEndpointList(results));
        summary.put("errorTypeSummary",     errorTypeSummary);
        summary.put("slowestEndpoints",     buildSlowestList(results, pFraction));
        return summary;
    }

    private Map<String, Object> buildGlobalStats(Map<String, SamplingStatCalculator> results,
                                                 int percentile, double pFraction,
                                                 LatencyContext latency) {
        Map<String, Object> global = new LinkedHashMap<>();
        SamplingStatCalculator total = results.get(TOTAL_LABEL);
        if (total == null || total.getCount() == 0) return global;

        final long totalCount = total.getCount();
        final long failedCount = Math.round(total.getErrorPercentage() * totalCount);

        global.put("totalRequests", totalCount);
        global.put("totalPassed", totalCount - failedCount);
        global.put("totalFailed", failedCount);
        global.put("configuredPercentile", percentile);
        global.put("avgResponseMs", round2(total.getMean()));
        global.put("medianResponseMs", round2(total.getPercentPoint(MEDIAN).doubleValue()));
        global.put("minResponseMs", total.getMin().longValue());
        global.put("maxResponseMs", total.getMax().longValue());
        global.put(percentile + "thPctMs", round2(total.getPercentPoint(pFraction).doubleValue()));
        global.put("p99ResponseMs", round2(total.getPercentPoint(0.99).doubleValue()));
        global.put("stdDevMs", round2(total.getStandardDeviation()));
        global.put(KEY_ERROR_RATE_PCT, round2(total.getErrorPercentage() * 100.0));
        global.put("throughputTPS", round2(total.getRate()));
        global.put("receivedBandwidthKBps", round2(total.getKBPerSecond()));
        // Latency decomposition fields — consumed by the Advanced Web Diagnostics
        // section of the AI prompt to decide between direct and inferred mode.
        global.put("avgLatencyMs",   latency.avgLatencyMs());
        global.put("avgConnectMs",   latency.avgConnectMs());
        global.put("latencyPresent", latency.latencyPresent());
        return global;
    }

    private List<Map<String, Object>> buildAnomalyTransactions(
            Map<String, SamplingStatCalculator> results, int percentile, double pFraction) {

        List<Map<String, Object>> anomalies = new ArrayList<>();
        final String pKey = percentile + "thPctMs";

        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            SamplingStatCalculator c = entry.getValue();
            final double avg = c.getMean();
            final double pVal = c.getPercentPoint(pFraction).doubleValue();
            final double errPct = c.getErrorPercentage() * 100.0;
            final double stdDev = c.getStandardDeviation();
            boolean isAnomaly = avg > THRESHOLD_AVG_MS
                    || pVal > THRESHOLD_PCT_MS
                    || errPct > THRESHOLD_ERROR_PCT
                    || (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO);
            if (TOTAL_LABEL.equals(entry.getKey()) || c.getCount() == 0 || !isAnomaly) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("count", cnt);
            ep.put("failed", failed);
            ep.put("avgMs", round2(avg));
            ep.put("medianMs", round2(c.getPercentPoint(MEDIAN).doubleValue()));
            ep.put(pKey, round2(pVal));
            ep.put("stdDevMs", round2(stdDev));
            ep.put(KEY_ERROR_RATE_PCT, round2(errPct));
            ep.put("throughputTPS", round2(c.getRate()));
            ep.put("receivedBandwidthKBps", round2(c.getKBPerSecond()));
            ep.put("breachedThresholds", buildBreachList(avg, pVal, errPct, stdDev, percentile));
            anomalies.add(ep);
        }

        anomalies.sort((a, b) ->
                Double.compare(asDouble(b.get(pKey)), asDouble(a.get(pKey))));
        return anomalies;
    }

    private List<String> buildBreachList(double avg, double pVal, double errPct,
                                         double stdDev, int percentile) {
        List<String> breaches = new ArrayList<>();
        if (avg > THRESHOLD_AVG_MS) breaches.add("avgMs > " + (int) THRESHOLD_AVG_MS + "ms");
        if (pVal > THRESHOLD_PCT_MS) breaches.add(percentile + "thPct > " + (int) THRESHOLD_PCT_MS + "ms");
        if (errPct > THRESHOLD_ERROR_PCT) breaches.add("errorRate > " + THRESHOLD_ERROR_PCT + "%");
        if (avg > 0 && stdDev / avg > THRESHOLD_STD_DEV_RATIO)
            breaches.add("highVariability (stdDev/avg=" + round2(stdDev / avg) + ")");
        return breaches;
    }

    private List<Map<String, Object>> buildErrorEndpointList(
            Map<String, SamplingStatCalculator> results) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            SamplingStatCalculator c = entry.getValue();
            if (TOTAL_LABEL.equals(entry.getKey()) || c.getCount() == 0 || c.getErrorPercentage() <= 0) continue;

            final long cnt = c.getCount();
            final long failed = Math.round(c.getErrorPercentage() * cnt);
            final double errPct = c.getErrorPercentage() * 100.0;

            Map<String, Object> ep = new LinkedHashMap<>();
            ep.put("label", entry.getKey());
            ep.put("errorCount", failed);
            ep.put("totalCount", cnt);
            ep.put(KEY_ERROR_RATE_PCT, round2(errPct));
            errors.add(ep);
        }
        errors.sort((a, b) ->
                Double.compare(asDouble(b.get(KEY_ERROR_RATE_PCT)), asDouble(a.get(KEY_ERROR_RATE_PCT))));
        return errors;
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private List<String> buildSlowestList(Map<String, SamplingStatCalculator> results,
                                          double pFraction) {
        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, SamplingStatCalculator> entry : results.entrySet()) {
            SamplingStatCalculator c = entry.getValue();
            if (TOTAL_LABEL.equals(entry.getKey()) || c.getCount() == 0) continue;
            ranked.add(Map.entry(entry.getKey(), c.getPercentPoint(pFraction).doubleValue()));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<String> top = new ArrayList<>();
        for (int i = 0; i < Math.min(SLOWEST_TOP_N, ranked.size()); i++) {
            Map.Entry<String, Double> e = ranked.get(i);
            top.add(e.getKey() + " (" + round2(e.getValue()) + " ms)");
        }
        return top;
    }
}