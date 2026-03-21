package com.personal.jmeter.parser;

import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.SamplingStatCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Two-pass CSV parser for JTL files.
 *
 * <p><b>Pass 1</b> — collect all labels and the minimum timestamp so that
 * sub-result detection and time-offset filtering are accurate.</p>
 * <p><b>Pass 2</b> — aggregate filtered samples into
 * {@link SamplingStatCalculator} instances and 30-second time buckets.</p>
 *
 * <p>Low-level parsing helpers are delegated to {@link JtlParserCore}.</p>
 * @since 4.6.0
 */
public class JTLParser {

    public static final String TOTAL_LABEL = "TOTAL";
    private static final Logger log = LoggerFactory.getLogger(JTLParser.class);
    /**
     * Target number of chart data points when chart interval is set to auto (0).
     */
    private static final int AUTO_BUCKET_TARGET = 120;

    /**
     * Clean snap intervals for auto bucket-size rounding, in milliseconds, ascending.
     * The computed raw interval is rounded up to the nearest value in this list so
     * that x-axis labels always land on round clock times (e.g. :00, :30, :00).
     */
    private static final long[] SNAP_INTERVALS_MS = {
            10_000L, 30_000L, 60_000L, 120_000L, 300_000L,
            600_000L, 1_800_000L, 3_600_000L
    };

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Computes the chart bucket size in milliseconds for auto mode
     * ({@code chartIntervalSeconds == 0}).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Compute actual JTL duration: {@code maxTimestamp - minTimestamp}.
     *       Both values come from Pass 1 — this correctly handles historical
     *       JTL files where wall-clock distance from the JTL start would be
     *       months or years, producing wildly inflated bucket sizes.</li>
     *   <li>Compute raw interval: {@code durationMs / AUTO_BUCKET_TARGET}.</li>
     *   <li>Snap up to the nearest value in {@link #SNAP_INTERVALS_MS} so that
     *       x-axis labels land on round clock times (e.g. :00, :30, :00).
     *       If the raw interval exceeds all snap values, use the largest.</li>
     * </ol>
     *
     * @param minTimestamp epoch-ms of the first JTL sample from Pass 1; 0 means unknown
     * @param maxTimestamp epoch-ms of the last JTL sample from Pass 1; 0 means unknown
     * @return bucket size in milliseconds, always &ge; {@code SNAP_INTERVALS_MS[0]}
     */
    static long computeAutoBucketSizeMs(long minTimestamp, long maxTimestamp) {
        long durationMs = (minTimestamp > 0 && maxTimestamp > minTimestamp)
                ? maxTimestamp - minTimestamp
                : 0L;

        // Guard: if duration is too short or unknown, fall back to smallest snap interval
        if (durationMs <= 0) {
            return SNAP_INTERVALS_MS[0];
        }

        long rawIntervalMs = durationMs / AUTO_BUCKET_TARGET;

        // Snap up to the nearest clean interval
        for (long snap : SNAP_INTERVALS_MS) {
            if (snap >= rawIntervalMs) {
                log.debug("computeAutoBucketSizeMs: durationMs={} rawInterval={}ms snapped={}ms",
                        durationMs, rawIntervalMs, snap);
                return snap;
            }
        }

        // Duration exceeds all snap values — use the largest (1 hour buckets)
        long largest = SNAP_INTERVALS_MS[SNAP_INTERVALS_MS.length - 1];
        log.debug("computeAutoBucketSizeMs: durationMs={} exceeds all snaps — using {}ms",
                durationMs, largest);
        return largest;
    }

    // ─────────────────────────────────────────────────────────────
    // Auto bucket-size computation
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses a JTL file and returns aggregated results with time metadata.
     *
     * @param filePath path to the JTL CSV file; must not be null
     * @param options  filter and display options (mutated: minTimestamp is set internally); must not be null
     * @return {@link ParseResult} containing per-label stats, time range, and time buckets
     * @throws IOException              if the file cannot be read or is empty
     * @throws IllegalArgumentException if filePath or options is null
     */
    public ParseResult parse(String filePath, FilterOptions options) throws IOException {
        Objects.requireNonNull(filePath, "filePath must not be null");
        Objects.requireNonNull(options, "options must not be null");

        log.info("parse: starting. filePath={}", filePath);

        // ── Pass 1: discover labels, minimum timestamp, and sub-result labels ────
        //
        // Sub-result detection uses the consecutive-row algorithm that mirrors how
        // JMeter writes Transaction Controller results to the JTL:
        //
        //   When "Generate Parent Sample" is ON, JMeter writes the Transaction
        //   Controller row (dataType = "") immediately before its child HTTP sample
        //   (dataType = "text") and gives both the identical timeStamp and elapsed.
        //
        //   A row whose label is preceded in the file by such a controller row is
        //   a sub-result and should be excluded when generateParentSample = true.
        //
        // This is distinct from the old numeric-suffix heuristic ("Foo-1", "Foo-2"),
        // which does not apply to Transaction Controller parent-child pairs.
        Set<String> allLabels = new HashSet<>();
        Set<String> subResultLabels = new HashSet<>();
        long minTimestamp = Long.MAX_VALUE;
        long maxTimestamp = Long.MIN_VALUE;

        // ── Header parse (M1) — done once here; Pass 2 reuses colMap ───────────
        // colMap and the four index variables are declared in the outer scope so
        // Pass 2 can reference them without rebuilding from the header line again.
        final Map<String, Integer> colMap;
        final Integer tsIdx, labelIdx, elapsedIdx, dataTypeIdx;
        final char delimiter = options.delimiter;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8), 65_536)) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new JtlParseException("JTL file is empty: " + filePath);

            colMap = JtlParserCore.buildColumnMap(headerLine, delimiter);
            tsIdx = colMap.get("timeStamp");
            labelIdx = colMap.get("label");
            elapsedIdx = colMap.get("elapsed");
            dataTypeIdx = colMap.get("dataType");

            // ── Pass 1 max-index (H2) ─────────────────────────────────────────
            // Determines how far extractPass1Fields needs to scan each line.
            // Only the four fields above are needed in Pass 1; all columns beyond
            // the highest of their indices are ignored, saving ~13 field allocations
            // per row on a standard 17-column JTL.
            int p1Max = 0;
            if (tsIdx != null) p1Max = Math.max(p1Max, tsIdx);
            if (labelIdx != null) p1Max = Math.max(p1Max, labelIdx);
            if (elapsedIdx != null) p1Max = Math.max(p1Max, elapsedIdx);
            if (dataTypeIdx != null) p1Max = Math.max(p1Max, dataTypeIdx);
            final int pass1MaxIdx = p1Max;

            // Previous-row fields for consecutive-row detection
            String prevTs = null, prevElapsed = null, prevDataType = null;
            // Flag: format detection runs exactly once on the first non-blank timestamp.
            boolean tsFormatDetected = false;

            String line;
            while ((line = reader.readLine()) != null) {
                // H2: extract only the 4 needed columns; stop scanning at pass1MaxIdx
                String[] values = JtlParserCore.extractPass1Fields(line, pass1MaxIdx, delimiter);

                String label = (labelIdx != null && labelIdx < values.length) ? values[labelIdx].trim() : "";
                String ts = (tsIdx != null && tsIdx < values.length) ? values[tsIdx].trim() : "";
                String elapsed = (elapsedIdx != null && elapsedIdx < values.length) ? values[elapsedIdx].trim() : "";
                String dataType = (dataTypeIdx != null && dataTypeIdx < values.length) ? values[dataTypeIdx].trim() : "";

                // Timestamp format resolution — runs once on the first non-blank ts value.
                // Step 1: auto-detect from file content (6 known patterns).
                // Step 2/3: TimestampFormatResolver pre-populated by caller (user/jmeter.properties).
                // Step 4: JtlParseException when format is unrecognised and not epoch-ms.
                if (!tsFormatDetected && !ts.isEmpty()) {
                    tsFormatDetected = true;
                    // Step 1: auto-detect format from the first timestamp value in the file.
                    // Wins over any format from properties files — the file content is
                    // always the most authoritative source.
                    DateTimeFormatter detected = JtlParserCore.detectTimestampFormatter(ts);
                    if (detected != null) {
                        options.timestampFormatter = detected;
                    } else if (options.timestampFormatter == null) {
                        // Steps 2 & 3: TimestampFormatResolver already pre-populated
                        // opts.timestampFormatter from user.properties (step 2) then
                        // jmeter.properties (step 3) before parse() was called.
                        // Both returned null — check if epoch-ms, else throw (step 4).
                        try {
                            Long.parseLong(ts);
                            // Parseable as epoch-ms — no formatter needed.
                        } catch (NumberFormatException e) {
                            // Step 4: format unrecognised by all steps — throw clear error.
                            throw new JtlParseException(
                                    "Unrecognised timestamp format in JTL file: \"" + ts + "\".\n"
                                            + "Set jmeter.save.saveservice.timestamp_format in "
                                            + "$JMETER_HOME/bin/user.properties or jmeter.properties "
                                            + "to match the format used in this file.");
                        }
                    }
                    // else: auto-detect returned null but resolver (steps 2/3) provided
                    // a formatter — keep opts.timestampFormatter as-is.
                }

                // Detect sub-result: prev row is a Transaction Controller (empty
                // dataType), this row is its child (non-empty dataType), both share
                // the same elapsed, and their timestamps are within 1 ms of each
                // other.  JMeter's Transaction Controller writes its parent row
                // immediately before the child with matching elapsed; the timestamps
                // are nominally identical but can differ by ±1 ms due to
                // sub-millisecond scheduling jitter on busy systems.
                if (options.generateParentSample
                        && prevDataType != null
                        && prevDataType.isEmpty()
                        && !dataType.isEmpty()
                        && !ts.isEmpty()
                        && !prevTs.isEmpty()
                        && elapsed.equals(prevElapsed)) {
                    long tsMs = JtlParserCore.parseTimestampMs(ts, options.timestampFormatter);
                    long prevTsMs = JtlParserCore.parseTimestampMs(prevTs, options.timestampFormatter);
                    if (tsMs > 0 && prevTsMs > 0 && Math.abs(tsMs - prevTsMs) <= 1) {
                        subResultLabels.add(label);
                    }
                }

                if (!label.isEmpty()) allLabels.add(label);

                if (!ts.isEmpty()) {
                    long tsLong = JtlParserCore.parseTimestampMs(ts, options.timestampFormatter);
                    if (tsLong > 0) {
                        if (tsLong < minTimestamp) minTimestamp = tsLong;
                        if (tsLong > maxTimestamp) maxTimestamp = tsLong;
                    }
                }

                prevTs = ts;
                prevElapsed = elapsed;
                prevDataType = dataType;
            }
        }
        options.minTimestamp = (minTimestamp == Long.MAX_VALUE) ? 0 : minTimestamp;

        // Numeric-suffix sub-result detection: "Foo-1", "Foo-2" are sub-results when
        // their parent label "Foo" also appears in the JTL (e.g. JMeter HTTP samplers
        // inside a Transaction Controller with Generate Parent Sample OFF, or any
        // sampler family that writes numbered child rows alongside a root row).
        for (String candidate : allLabels) {
            int dashIdx = candidate.lastIndexOf('-');
            if (dashIdx > 0 && dashIdx < candidate.length() - 1) {
                String suffix = candidate.substring(dashIdx + 1);
                String parent = candidate.substring(0, dashIdx);
                if (!suffix.isEmpty()
                        && suffix.chars().allMatch(Character::isDigit)
                        && allLabels.contains(parent)) {
                    subResultLabels.add(candidate);
                }
            }
        }

        // ── Pass 2: aggregate ────────────────────────────────────
        // Resolve the chart bucket size:
        //   - User-configured value (chartIntervalSeconds > 0) is used as-is.
        //   - Auto (chartIntervalSeconds == 0): compute from actual JTL duration
        //     (maxTimestamp − minTimestamp from Pass 1) so the chart always targets
        //     ~AUTO_BUCKET_TARGET data points, then snap up to the nearest clean
        //     interval (10s … 3600s) so x-axis labels land on round clock times.
        //     Using JTL timestamps — not System.currentTimeMillis() — is essential
        //     for historical JTL files where wall-clock distance would be years.
        final long p1MaxTimestamp = (maxTimestamp == Long.MIN_VALUE) ? 0 : maxTimestamp;
        final long bucketSizeMs = (options.chartIntervalSeconds > 0)
                ? options.chartIntervalSeconds * 1_000L
                : computeAutoBucketSizeMs(options.minTimestamp, p1MaxTimestamp);

        Map<String, SamplingStatCalculator> results = new LinkedHashMap<>();
        SamplingStatCalculator totalCalc = new SamplingStatCalculator(TOTAL_LABEL);
        long testStartMs = Long.MAX_VALUE;
        long testEndMs = Long.MIN_VALUE;
        TreeMap<Long, long[]> bucketMap = new TreeMap<>();
        // Error-type accumulator: "responseCode | responseMessage" → count
        Map<String, Long> errorTypeCount = new HashMap<>(); // insertion order unused — sorted by frequency in buildErrorTypeSummary()
        // Latency / Connect accumulators for Advanced Web Diagnostics
        long totalLatencyMs = 0L;
        long totalConnectMs = 0L;
        int latencySampleCount = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath), StandardCharsets.UTF_8), 65_536)) {
            reader.readLine(); // M1: skip header — colMap already built in Pass 1

            // M2: pre-compute filter flags once — invariant for the entire parse.
            // Avoids recomputing isBlank() and int comparisons on every row in the
            // hot loop, which is significant for JTL files with 200K+ samples.
            final boolean hasInclude = !options.includeLabels.isBlank();
            final boolean hasExclude = !options.excludeLabels.isBlank();
            final boolean hasOffset = options.startOffset > 0 || options.endOffset > 0;

            String line;

            while ((line = reader.readLine()) != null) {
                // H1: tokenise once — tokens shared by parseLine and parseElapsed,
                // eliminating the second splitCsvLine call that parseElapsed previously
                // made on the same line.
                String[] tokens = JtlParserCore.splitCsvLine(line, delimiter);
                SampleResult sr = JtlParserCore.parseLineTokens(tokens, colMap);
                if (sr == null) continue;

                // Re-parse timestamp when it is 0 — indicates a formatted date string
                // (e.g. "2023/11/15 10:30:00.123") that Long.parseLong could not read.
                // Must happen before shouldInclude, which uses sr.getTimeStamp() for
                // start/end offset filtering.
                if (sr.getTimeStamp() == 0
                        && tsIdx != null && tsIdx < tokens.length
                        && !tokens[tsIdx].isEmpty()) {
                    long parsedTs = JtlParserCore.parseTimestampMs(
                            tokens[tsIdx], options.timestampFormatter);
                    if (parsedTs > 0) sr.setTimeStamp(parsedTs);
                }

                if (subResultLabels.contains(sr.getSampleLabel())
                        || !JtlParserCore.shouldInclude(sr, options, hasInclude, hasExclude, hasOffset)) {
                    continue;
                }

                // Compute elapsed; optionally subtract IdleTime (timers / pre-post
                // processors) before calling setStampAndTime — which must be called
                // exactly once on a SampleResult.
                long rawElapsed = JtlParserCore.parseElapsedTokens(tokens, colMap);
                long adjusted = (!options.includeTimerDuration && sr.getIdleTime() > 0)
                        ? Math.max(0L, rawElapsed - sr.getIdleTime())
                        : rawElapsed;
                sr.setStampAndTime(sr.getTimeStamp(), adjusted);

                // Accumulate Latency and Connect for Advanced Web Diagnostics averages.
                // latencySampleCount counts only non-zero Latency rows: a row with
                // Latency=0 means the column is absent or was never populated, which
                // is distinct from true zero-latency (HTTP keep-alive, Connect=0 only).
                totalLatencyMs += sr.getLatency();
                totalConnectMs += sr.getConnectTime();
                if (sr.getLatency() > 0) latencySampleCount++;

                String label = sr.getSampleLabel();
                results.computeIfAbsent(label, SamplingStatCalculator::new).addSample(sr);
                totalCalc.addSample(sr);

                // Accumulate failure type for errorTypeSummary
                if (!sr.isSuccessful()) {
                    String key = (sr.getResponseCode() + " | " + sr.getResponseMessage()).trim();
                    errorTypeCount.merge(key, 1L, Long::sum);
                }

                // Read sampleStart from the raw token directly — immune to any
                // SampleResult internal state changes caused by setStampAndTime()
                // or JMeter version differences in getTimeStamp() behaviour.
                // rawElapsed is already on the stack; sampleEnd uses it directly.
                long sampleStart = (tsIdx != null && tsIdx < tokens.length)
                        ? JtlParserCore.parseTimestampMs(tokens[tsIdx], options.timestampFormatter)
                        : sr.getTimeStamp();
                long sampleEnd = sampleStart + rawElapsed;
                if (sampleStart > 0 && sampleStart < testStartMs) testStartMs = sampleStart;
                if (sampleEnd > testEndMs) testEndMs = sampleEnd;

                // Test-aligned bucket key: anchor to options.minTimestamp so the
                // first bucket always starts exactly at test start, not at the
                // nearest epoch boundary. Epoch-aligned keys (sampleStart / bktMs * bktMs)
                // cause a partial first bucket when test start is not on a clean boundary,
                // which cascades to also drop the last bucket via the coverage filter.
                long bucketKey = ((sampleStart - options.minTimestamp) / bucketSizeMs)
                        * bucketSizeMs + options.minTimestamp;
                long[] acc = bucketMap.computeIfAbsent(bucketKey, k -> new long[4]);
                acc[0] += sr.getTime();
                acc[1] += 1;
                acc[2] += sr.isSuccessful() ? 0 : 1;
                acc[3] += sr.getBytesAsLong();
            }
        }

        if (!results.isEmpty()) results.put(TOTAL_LABEL, totalCalc);
        if (testStartMs == Long.MAX_VALUE) testStartMs = 0;
        if (testEndMs == Long.MIN_VALUE) testEndMs = 0;

        // Derive average Latency and Connect from the accumulated totals.
        // Divide by totalSampleCount (not latencySampleCount) so the averages are
        // comparable to avgResponseMs — i.e. averaged over all samples, not just
        // those where Latency > 0.  latencyPresent drives the prompt's branch logic.
        final long totalSampleCount = totalCalc.getCount();
        final long avgLatencyMs = (latencySampleCount > 0 && totalSampleCount > 0)
                ? totalLatencyMs / totalSampleCount : 0L;
        final long avgConnectMs = (latencySampleCount > 0 && totalSampleCount > 0)
                ? totalConnectMs / totalSampleCount : 0L;
        final boolean latencyPresent = latencySampleCount > 0;

        List<TimeBucket> timeBuckets = JtlParserCore.buildTimeBuckets(
                bucketMap, bucketSizeMs, testStartMs, testEndMs);
        log.info("parse: completed. labels={}, samples={}, buckets={}, latencyPresent={}",
                results.size(), totalCalc.getCount(), timeBuckets.size(), latencyPresent);
        return new ParseResult(results, testStartMs, testEndMs, timeBuckets, errorTypeCount,
                avgLatencyMs, avgConnectMs, latencyPresent);
    }

    // ─────────────────────────────────────────────────────────────
    // Public data classes
    // ─────────────────────────────────────────────────────────────

    /**
     * Aggregated parse output returned by {@link #parse}.
     */
    public static class ParseResult {
        private static final int MAX_ERROR_TYPES = 5;
        /**
         * Shared display format — matches the format used in the GUI table and CLI output.
         * Single source of truth: avoids the duplicated {@code DateTimeFormatter} constants
         * that previously lived in {@code AggregateReportPanel} and {@code CliReportPipeline}.
         */
        private static final DateTimeFormatter DISPLAY_TIME_FORMAT =
                DateTimeFormatter.ofPattern("MM/dd/yy HH:mm:ss");
        /**
         * Per-label aggregated statistics.
         */
        public final Map<String, SamplingStatCalculator> results;
        /**
         * Epoch millis of the first sample timestamp.
         */
        public final long startTimeMs;
        /**
         * Epoch millis of the last sample end (timestamp + elapsed).
         */
        public final long endTimeMs;
        /**
         * Total test duration in milliseconds.
         */
        public final long durationMs;
        /**
         * Ordered list of 30-second time buckets.
         */
        public final List<TimeBucket> timeBuckets;
        /**
         * Top-5 failure types by frequency, each entry containing:
         * {@code responseCode}, {@code responseMessage}, {@code count}.
         * Empty list when no failures occurred.
         */
        public final List<Map<String, Object>> errorTypeSummary;
        /**
         * Average Latency (TTFB) in milliseconds across all filtered samples.
         * Zero when the {@code Latency} column is absent from the JTL or every
         * row has {@code Latency = 0} (i.e. {@link #latencyPresent} is false).
         */
        public final long avgLatencyMs;
        /**
         * Average Connect time in milliseconds across all filtered samples.
         * Zero when {@link #latencyPresent} is false.
         */
        public final long avgConnectMs;
        /**
         * {@code true} when at least one sample has a non-zero {@code Latency} value.
         * Drives the {@code latencyPresent} branch in the AI prompt's
         * Advanced Web Diagnostics section.
         */
        public final boolean latencyPresent;

        /**
         * Constructs a parse result.
         *
         * @param results        per-label aggregated statistics
         * @param startTimeMs    epoch millis of first sample
         * @param endTimeMs      epoch millis of last sample end
         * @param timeBuckets    ordered list of 30-second time buckets
         * @param errorTypeCount raw "responseCode | responseMessage" → count map
         * @param avgLatencyMs   average Latency ms (0 if absent or all-zero)
         * @param avgConnectMs   average Connect ms (0 if latencyPresent is false)
         * @param latencyPresent true iff ≥ 1 non-zero Latency value was seen
         */
        public ParseResult(Map<String, SamplingStatCalculator> results,
                           long startTimeMs, long endTimeMs,
                           List<TimeBucket> timeBuckets,
                           Map<String, Long> errorTypeCount,
                           long avgLatencyMs, long avgConnectMs, boolean latencyPresent) {
            this.results = results;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.durationMs = Math.max(0, endTimeMs - startTimeMs);
            this.timeBuckets = timeBuckets != null ? timeBuckets : Collections.emptyList();
            this.errorTypeSummary = buildErrorTypeSummary(errorTypeCount);
            this.avgLatencyMs = avgLatencyMs;
            this.avgConnectMs = avgConnectMs;
            this.latencyPresent = latencyPresent;
        }

        private static String formatEpochMs(long epochMs) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
                    .format(DISPLAY_TIME_FORMAT);
        }

        private static List<Map<String, Object>> buildErrorTypeSummary(
                Map<String, Long> errorTypeCount) {
            if (errorTypeCount == null || errorTypeCount.isEmpty())
                return Collections.emptyList();

            return errorTypeCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(MAX_ERROR_TYPES)
                    .map(e -> {
                        String[] parts = e.getKey().split(" \\| ", 2);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("responseCode", parts[0].trim());
                        m.put("responseMessage", parts.length > 1 ? parts[1].trim() : "");
                        m.put("count", e.getValue());
                        return m;
                    })
                    .toList();
        }

        /**
         * Returns the test start time as a formatted display string, or an empty string
         * if {@link #startTimeMs} is zero.
         *
         * @return formatted start time, e.g. {@code "03/11/26 14:30:00"}, or {@code ""}
         */
        public String formattedStartTime() {
            return startTimeMs > 0 ? formatEpochMs(startTimeMs) : "";
        }

        /**
         * Returns the test end time as a formatted display string, or an empty string
         * if {@link #endTimeMs} is zero.
         *
         * @return formatted end time, e.g. {@code "03/11/26 15:00:00"}, or {@code ""}
         */
        public String formattedEndTime() {
            return endTimeMs > 0 ? formatEpochMs(endTimeMs) : "";
        }

        /**
         * Returns the test duration as a formatted display string, or an empty string
         * if {@link #durationMs} is zero.
         *
         * @return formatted duration, e.g. {@code "0h 30m 0s"}, or {@code ""}
         */
        public String formattedDuration() {
            if (durationMs <= 0) return "";
            long s = durationMs / 1000;
            return String.format("%dh %dm %ds", s / 3600, (s % 3600) / 60, s % 60);
        }
    }

    /**
     * Aggregated metrics for a single 30-second time bucket.
     */
    public static class TimeBucket {
        /**
         * Epoch millis representing the start of the bucket.
         */
        public final long epochMs;
        /**
         * Average response time in milliseconds for this bucket.
         */
        public final double avgResponseMs;
        /**
         * Percentage of failed requests in this bucket.
         */
        public final double errorPct;
        /**
         * Transactions per second in this bucket.
         */
        public final double tps;
        /**
         * Kilobytes per second received in this bucket.
         */
        public final double kbps;

        /**
         * Constructs a time bucket.
         *
         * @param epochMs       epoch millis representing the start of the bucket
         * @param avgResponseMs average response time in milliseconds
         * @param errorPct      percentage of failed requests
         * @param tps           transactions per second
         * @param kbps          kilobytes per second received
         */
        public TimeBucket(long epochMs, double avgResponseMs,
                          double errorPct, double tps, double kbps) {
            this.epochMs = epochMs;
            this.avgResponseMs = avgResponseMs;
            this.errorPct = errorPct;
            this.tps = tps;
            this.kbps = kbps;
        }
    }

    /**
     * Filter and display options passed to {@link #parse}.
     */
    public static class FilterOptions {
        /**
         * Label substring or regex to include; blank means include all.
         */
        public String includeLabels = "";
        /**
         * Label substring or regex to exclude; blank means exclude none.
         */
        public String excludeLabels = "";
        /**
         * If {@code true}, treat include/exclude patterns as regular expressions.
         */
        public boolean regExp = false;
        /**
         * Seconds from test start to begin including samples.
         */
        public int startOffset = 0;
        /**
         * Seconds from test start to stop including samples (0 = no limit).
         */
        public int endOffset = 0;
        /**
         * Percentile to calculate (1–99).
         */
        public int percentile = 90;
        /**
         * JTL field delimiter resolved from JMeter's properties files.
         *
         * <p>Default: {@code ','} (standard CSV). Resolved at parse-call time by
         * {@link DelimiterResolver#resolve(java.io.File)} — callers set this field
         * before passing the options to {@link JTLParser#parse}.</p>
         *
         * <p>Supported values: any single character (e.g. {@code ','}, {@code ';'},
         * {@code '|'}, {@code '\t'}). Multi-character delimiters are not supported.</p>
         */
        public char delimiter = ',';
        /**
         * Mirrors JMeter's Transaction Controller "Generate Parent Sample" checkbox.
         *
         * <p>{@code true} (default / ON) — sub-results are detected and excluded so
         * only parent/controller-level rows appear in the table, matching standard
         * JMeter Aggregate Report behaviour.</p>
         *
         * <p>{@code false} (OFF) — sub-result detection is skipped; every label that
         * appears in the JTL is aggregated as its own row.</p>
         */
        public boolean generateParentSample = true;
        /**
         * Mirrors JMeter's Aggregate Report "Include duration of timer and
         * pre-post processors in generated sample" checkbox.
         *
         * <p>{@code true} (default / ON) — the raw {@code elapsed} value from the JTL
         * is used as-is, matching standard JMeter Aggregate Report behaviour.</p>
         *
         * <p>{@code false} (OFF) — the {@code IdleTime} column value (time spent
         * waiting in timers and pre/post processors) is subtracted from each sample's
         * elapsed time before aggregation, yielding net processing-only response times.</p>
         */
        public boolean includeTimerDuration = true;
        /**
         * Chart time-bucket interval in seconds. {@code 0} (default) means auto-calculate.
         *
         * <p>Auto mode targets {@value JTLParser#AUTO_BUCKET_TARGET} data points by
         * deriving the raw interval from the test duration, then snapping up to the nearest
         * clean value (10 s, 30 s, 60 s, 120 s, 300 s, 600 s, 1800 s, 3600 s) so that
         * x-axis labels always land on round clock times.</p>
         *
         * <p>When the user sets a positive value via the "Chart Interval" field, the
         * parser uses that value (converted to milliseconds) as the bucket width for
         * time-series charts.</p>
         */
        public int chartIntervalSeconds = 0;
        /**
         * {@link java.time.format.DateTimeFormatter} for parsing formatted timestamps
         * from JTL/CSV files (e.g. {@code 2026/03/20 14:08:39.123}).
         *
         * <p>{@code null} (default) = epoch-millisecond mode — standard JMeter behaviour.</p>
         *
         * <h4>Resolution order (applied in {@link JTLParser#parse})</h4>
         * <ol>
         *   <li><b>Auto-detection</b> — {@link JtlParserCore#detectTimestampFormatter}
         *       inspects the first timestamp value in the file and matches it against
         *       6 known JMeter datetime patterns. Wins when a match is found.</li>
         *   <li><b>user.properties</b> — {@code jmeter.save.saveservice.timestamp_format}
         *       from {@code $JMETER_HOME/bin/user.properties}; pre-populated by callers
         *       via {@link TimestampFormatResolver#resolve} before calling {@code parse}.</li>
         *   <li><b>jmeter.properties</b> — same property from
         *       {@code $JMETER_HOME/bin/jmeter.properties}; fallback when step 2 absent.</li>
         *   <li><b>Error</b> — {@link JtlParseException} thrown when the format is not
         *       epoch-ms and all above steps fail.</li>
         * </ol>
         */
        public java.time.format.DateTimeFormatter timestampFormatter = null;
        /**
         * Set internally during parse — tracks the test start timestamp.
         */
        long minTimestamp = 0;
    }
}