package com.personal.jmeter.parser;

import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Low-level CSV parsing and aggregation helpers for {@link JTLParser}.
 *
 * <p>Extracted from {@link JTLParser} to satisfy the 300-line class design
 * limit (Standard 3 SRP). Responsibility: per-line parsing and data
 * transformation only — no file I/O, no public API surface.</p>
 *
 * <p>All methods are package-private statics; callers do not need an instance.</p>
 */
final class JtlParserCore {

    private static final Logger log = LoggerFactory.getLogger(JtlParserCore.class);

    private JtlParserCore() { /* static utility — not instantiable */ }

    // ─────────────────────────────────────────────────────────────
    // Bucket helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Converts bucket accumulators into an ordered {@link JTLParser.TimeBucket} list,
     * dropping partial buckets at the start and end of the test.
     *
     * <p>A bucket is considered partial when its effective coverage — the overlap
     * between the bucket window and the filtered test time range — is less than
     * {@value #MIN_BUCKET_COVERAGE_RATIO} of {@code bucketSizeMs}. Partial buckets
     * produce artificially deflated TPS and KB/s values because the denominator
     * ({@code bucketSizeMs}) is larger than the actual sample window, causing the
     * chart to misrepresent throughput at the edges of the test run.</p>
     *
     * <p>Example: a 30-second bucket containing only 7 seconds of samples at the
     * end of the test would show TPS as {@code count / 30}, understating it by 4x.
     * Dropping it removes the misleading data point without affecting the table or
     * AI analysis, which read from {@link org.apache.jmeter.visualizers.SamplingStatCalculator}
     * directly.</p>
     *
     * @param bucketMap    raw accumulator map from Pass 2 (key = bucket epoch ms)
     * @param bucketSizeMs bucket interval in milliseconds
     * @param testStartMs  epoch ms of the first included sample (post-filter)
     * @param testEndMs    epoch ms of the last included sample end (post-filter)
     * @return ordered list of complete time buckets; partial edge buckets are excluded
     */
    static List<JTLParser.TimeBucket> buildTimeBuckets(TreeMap<Long, long[]> bucketMap,
                                                       long bucketSizeMs,
                                                       long testStartMs,
                                                       long testEndMs) {
        List<JTLParser.TimeBucket> list = new ArrayList<>(bucketMap.size());
        long minCoverageMs  = (long) (bucketSizeMs * MIN_BUCKET_COVERAGE_RATIO);

        // Guard: if the entire test duration fits within one bucket, there are no
        // partial edge buckets to drop — every bucket IS the complete data.
        // Skip the coverage check entirely in this case.
        long testDurationMs = (testStartMs > 0 && testEndMs > testStartMs)
                ? testEndMs - testStartMs : 0L;
        boolean applyCoverageFilter = testDurationMs > bucketSizeMs;

        for (Map.Entry<Long, long[]> e : bucketMap.entrySet()) {
            long bucketStart = e.getKey();
            long bucketEnd   = bucketStart + bucketSizeMs;

            // Effective coverage = overlap between bucket window and filtered test range.
            // Buckets outside [testStartMs, testEndMs] are already absent from bucketMap;
            // this check catches edge buckets that are only partially covered.
            long effectiveStart      = Math.max(bucketStart, testStartMs);
            long effectiveEnd        = Math.min(bucketEnd,   testEndMs);
            long effectiveCoverageMs = effectiveEnd - effectiveStart;

            if (applyCoverageFilter && effectiveCoverageMs < minCoverageMs) {
                log.debug("buildTimeBuckets: dropping partial bucket at {}ms " +
                                "(coverage {}ms < minimum {}ms)",
                        bucketStart, effectiveCoverageMs, minCoverageMs);
                continue;
            }

            long[] acc    = e.getValue();
            long   count  = acc[1];
            // Use effectiveCoverageMs as the denominator when it is meaningful
            // (applyCoverageFilter is true and coverage is a proper sub-window).
            // Fall back to full bucketSizeMs for short tests where the single
            // bucket represents the whole test — avoids artificially inflating TPS.
            double effectiveSec = (applyCoverageFilter && effectiveCoverageMs > 0)
                    ? effectiveCoverageMs / 1000.0
                    : bucketSizeMs / 1000.0;
            double avgRt  = count > 0 ? (double) acc[0] / count : 0.0;
            double errPct = count > 0 ? (double) acc[2] / count * 100.0 : 0.0;
            double tps    = count / effectiveSec;
            double kbps   = (double) acc[3] / effectiveSec / 1024.0;
            list.add(new JTLParser.TimeBucket(bucketStart, avgRt, errPct, tps, kbps));
        }
        return list;
    }

    /**
     * Minimum fraction of {@code bucketSizeMs} that a bucket's effective sample
     * coverage must meet to be included in the chart output.
     * Buckets below this threshold are partial edge buckets whose TPS and KB/s
     * values would be artificially deflated by the full-bucket-size denominator.
     */
    private static final double MIN_BUCKET_COVERAGE_RATIO = 0.90;

    // ─────────────────────────────────────────────────────────────
    // CSV parsing
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a column-name-to-index map from the JTL header line.
     *
     * @param headers raw header tokens (split on comma before call)
     * @return map from trimmed column name to its zero-based index
     */
    static Map<String, Integer> buildColumnMap(String[] headers) {
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim(), i);
        }
        return map;
    }

    /**
     * Builds a column-name-to-index map from the raw JTL header line,
     * splitting on the specified delimiter.
     *
     * @param headerLine raw header line (unsplit)
     * @param delimiter  field delimiter character
     * @return map from trimmed column name to its zero-based index
     */
    static Map<String, Integer> buildColumnMap(String headerLine, char delimiter) {
        return buildColumnMap(splitCsvLine(headerLine, delimiter));
    }

    /**
     * Lightweight Pass-1 field extractor using the default comma delimiter.
     * Backward-compatible overload for callers without a delimiter reference.
     *
     * @param line   raw CSV data line (not the header); must not be null
     * @param maxIdx stop tokenising after this column index (zero-based, inclusive)
     * @return array of length {@code maxIdx + 1} with trimmed, unquoted field values
     */
    static String[] extractPass1Fields(String line, int maxIdx) {
        return extractPass1Fields(line, maxIdx, ',');
    }

    /**
     * Lightweight Pass-1 field extractor — tokenises only up to column
     * {@code maxIdx} (inclusive) and returns early, avoiding allocation of
     * {@link String} tokens for the remaining columns.
     *
     * <p>Uses the same quote-aware, trim-on-emit logic as {@link #splitCsvLine}
     * so {@code label} fields that contain the delimiter are handled correctly.</p>
     *
     * <p>If the line has fewer columns than {@code maxIdx + 1}, the missing
     * positions in the returned array are returned as {@code ""}.</p>
     *
     * @param line      raw CSV data line (not the header); must not be null
     * @param maxIdx    stop tokenising after this column index (zero-based, inclusive)
     * @param delimiter field delimiter character
     * @return array of length {@code maxIdx + 1} with trimmed, unquoted field values
     */
    static String[] extractPass1Fields(String line, int maxIdx, char delimiter) {
        String[] result = new String[maxIdx + 1];
        Arrays.fill(result, "");
        int     fieldIdx = 0;
        StringBuilder current = new StringBuilder(32);
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                result[fieldIdx] = current.toString().trim();
                if (fieldIdx == maxIdx) return result;  // early exit — all needed fields collected
                current.setLength(0);
                fieldIdx++;
            } else {
                current.append(c);
            }
        }
        // Store the last field (no trailing delimiter on the final column)
        if (fieldIdx <= maxIdx) {
            result[fieldIdx] = current.toString().trim();
        }
        return result;
    }

    /**
     * Parses one CSV line into a {@link SampleResult}.
     * Returns {@code null} for blank lines or lines that cannot be parsed.
     *
     * <p>Delegates to {@link #parseLine(String[], Map)} after tokenising.
     * Call {@link #parseLine(String[], Map)} directly when the caller already
     * holds the token array (Pass 2 hot loop) to avoid a second
     * {@link #splitCsvLine} call.</p>
     *
     * @param line   one CSV data line (not the header)
     * @param colMap column-name-to-index map built by {@link #buildColumnMap}
     * @return populated {@link SampleResult}, or {@code null} if the line is invalid
     */
    static SampleResult parseLine(String line, Map<String, Integer> colMap) {
        if (line == null || line.isBlank()) return null;
        return parseLineTokens(splitCsvLine(line), colMap);
    }

    /**
     * Parses one CSV line into a {@link SampleResult} using the specified delimiter.
     *
     * @param line      one CSV data line (not the header)
     * @param colMap    column-name-to-index map built by {@link #buildColumnMap}
     * @param delimiter field delimiter character
     * @return populated {@link SampleResult}, or {@code null} if the line is invalid
     */
    static SampleResult parseLine(String line, Map<String, Integer> colMap, char delimiter) {
        if (line == null || line.isBlank()) return null;
        return parseLineTokens(splitCsvLine(line, delimiter), colMap);
    }

    /**
     * Parses a pre-tokenised CSV row into a {@link SampleResult}.
     * Returns {@code null} if the token array cannot be parsed.
     *
     * <p>Preferred over {@link #parseLine(String, Map)} in the Pass 2 hot
     * loop because the caller already holds the token array from a single
     * {@link #splitCsvLine} call, eliminating a redundant tokenisation.</p>
     *
     * <p><b>NOTE:</b> {@code setStampAndTime()} is intentionally NOT called here.
     * The caller ({@link JTLParser#parse}) calls it exactly once, after optionally
     * adjusting the elapsed value, to avoid the {@link IllegalStateException} that
     * {@link org.apache.jmeter.samplers.SampleResult} throws when
     * {@code setStampAndTime()} is called more than once.</p>
     *
     * @param tokens pre-tokenised field array from {@link #splitCsvLine}
     * @param colMap column-name-to-index map built by {@link #buildColumnMap}
     * @return populated {@link SampleResult}, or {@code null} on failure
     */
    static SampleResult parseLineTokens(String[] tokens, Map<String, Integer> colMap) {
        try {
            SampleResult sr = new SampleResult();
            sr.setTimeStamp(getLong(tokens, colMap, "timeStamp", 0));
            sr.setSampleLabel(getString(tokens, colMap, "label", "unknown"));
            sr.setResponseCode(getString(tokens, colMap, "responseCode", ""));
            sr.setResponseMessage(getString(tokens, colMap, "responseMessage", ""));
            sr.setThreadName(getString(tokens, colMap, "threadName", ""));
            sr.setDataType(getString(tokens, colMap, "dataType", ""));
            sr.setSuccessful("true".equalsIgnoreCase(getString(tokens, colMap, "success", "true")));
            sr.setBytes(getLong(tokens, colMap, "bytes", 0));
            sr.setSentBytes(getLong(tokens, colMap, "sentBytes", 0));
            sr.setLatency(getLong(tokens, colMap, "Latency", 0));
            sr.setIdleTime(getLong(tokens, colMap, "IdleTime", 0));
            sr.setConnectTime(getLong(tokens, colMap, "Connect", 0));
            return sr;
        } catch (IllegalArgumentException e) {
            if (log.isDebugEnabled()) {
                log.debug("parseLine: malformed CSV tokens skipped. reason={}", e.getMessage());
            }
            return null;
        }
    }

    /**
     * Extracts the raw {@code elapsed} value from a CSV line.
     *
     * <p>Delegates to {@link #parseElapsed(String[], Map)} after tokenising.
     * Call {@link #parseElapsed(String[], Map)} directly when the caller already
     * holds the token array (Pass 2 hot loop) to avoid a second
     * {@link #splitCsvLine} call.</p>
     *
     * @param line   one CSV data line (not the header)
     * @param colMap column-name-to-index map built by {@link #buildColumnMap}
     * @return elapsed in milliseconds, or {@code 0} if absent/malformed
     */
    static long parseElapsed(String line, Map<String, Integer> colMap) {
        if (line == null || line.isBlank()) return 0;
        return parseElapsedTokens(splitCsvLine(line), colMap);
    }

    /**
     * Extracts the raw {@code elapsed} value using the specified delimiter.
     *
     * @param line      one CSV data line (not the header)
     * @param colMap    column-name-to-index map built by {@link #buildColumnMap}
     * @param delimiter field delimiter character
     * @return elapsed in milliseconds, or {@code 0} if absent/malformed
     */
    static long parseElapsed(String line, Map<String, Integer> colMap, char delimiter) {
        if (line == null || line.isBlank()) return 0;
        return parseElapsedTokens(splitCsvLine(line, delimiter), colMap);
    }

    /**
     * Extracts the raw {@code elapsed} value from a pre-tokenised CSV row.
     *
     * <p>Preferred over {@link #parseElapsed(String, Map)} in the Pass 2 hot
     * loop because the caller already holds the token array, eliminating a
     * redundant {@link #splitCsvLine} call.</p>
     *
     * @param tokens pre-tokenised field array from {@link #splitCsvLine}
     * @param colMap column-name-to-index map built by {@link #buildColumnMap}
     * @return elapsed in milliseconds, or {@code 0} if absent/malformed
     */
    static long parseElapsedTokens(String[] tokens, Map<String, Integer> colMap) {
        return getLong(tokens, colMap, "elapsed", 0);
    }

    /**
     * Splits a CSV line using the default comma delimiter.
     * Backward-compatible overload for callers without a delimiter reference.
     *
     * @param line raw CSV line
     * @return array of unquoted, trimmed field values
     */
    static String[] splitCsvLine(String line) {
        return splitCsvLine(line, ',');
    }

    /**
     * Splits a CSV line using the specified delimiter.
     *
     * @param line      raw CSV line
     * @param delimiter field delimiter character
     * @return array of unquoted, trimmed field values
     */
    static String[] splitCsvLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>(18);      // 17-col JTL + 1 headroom → zero resizes
        StringBuilder current = new StringBuilder(32);  // covers most field values without resize
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields.toArray(new String[fields.size()]); // sized array avoids reflection fallback
    }

    // ─────────────────────────────────────────────────────────────
    // Filter
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code sr} should be included based on {@code options}.
     *
     * <p>Delegates to {@link #shouldInclude(SampleResult, JTLParser.FilterOptions, boolean, boolean, boolean)}
     * after deriving the three flag values from {@code options}. Use the three-flag
     * overload directly from the Pass 2 hot loop to avoid recomputing the flags on
     * every row.</p>
     *
     * @param sr      the sample to test
     * @param options current filter options
     * @return {@code true} to include, {@code false} to exclude
     */
    static boolean shouldInclude(SampleResult sr, JTLParser.FilterOptions options) {
        return shouldInclude(sr, options,
                !options.includeLabels.isBlank(),
                !options.excludeLabels.isBlank(),
                options.startOffset > 0 || options.endOffset > 0);
    }

    /**
     * Returns {@code true} if {@code sr} should be included based on {@code options}.
     *
     * <p>The three boolean flags are invariant for the entire parse and must be
     * pre-computed once before the Pass 2 loop by the caller. This eliminates
     * redundant {@code isBlank()} scans and int comparisons on every row —
     * significant for large JTL files with 200K+ samples.</p>
     *
     * @param sr         the sample to test
     * @param options    current filter options
     * @param hasInclude {@code true} when {@code options.includeLabels} is non-blank
     * @param hasExclude {@code true} when {@code options.excludeLabels} is non-blank
     * @param hasOffset  {@code true} when either {@code startOffset} or {@code endOffset} is &gt; 0
     * @return {@code true} to include, {@code false} to exclude
     */
    static boolean shouldInclude(SampleResult sr, JTLParser.FilterOptions options,
                                 boolean hasInclude, boolean hasExclude, boolean hasOffset) {
        String label = sr.getSampleLabel();
        if (hasInclude) {
            boolean found = options.regExp
                    ? label.matches(options.includeLabels)
                    : label.contains(options.includeLabels);
            if (!found) return false;
        }
        if (hasExclude) {
            boolean excluded = options.regExp
                    ? label.matches(options.excludeLabels)
                    : label.contains(options.excludeLabels);
            if (excluded) return false;
        }
        if (hasOffset) {
            long relativeSec = (sr.getTimeStamp() - options.minTimestamp) / 1000L;
            if (options.startOffset > 0 && relativeSec < options.startOffset) return false;
            if (options.endOffset   > 0 && relativeSec > options.endOffset)   return false;
        }
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    // Field extraction helpers
    // ─────────────────────────────────────────────────────────────

    static String getString(String[] values, Map<String, Integer> map,
                            String column, String defaultValue) {
        Integer index = map.get(column);
        if (index == null || index >= values.length) return defaultValue;
        String value = values[index].trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    static long getLong(String[] values, Map<String, Integer> map,
                        String column, long defaultValue) {
        String str = getString(values, map, column, "");
        if (str.isEmpty()) return defaultValue;
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

}