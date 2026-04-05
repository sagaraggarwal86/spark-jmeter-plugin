package com.personal.jmeter.ai.report;

import com.personal.jmeter.parser.JTLParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts an AI-generated Markdown report into a styled, standalone HTML file
 * and writes it next to the source JTL file.
 *
 * <p>Report layout:</p>
 * <ol>
 *   <li>Header ({@code .rpt-header}) — title, AI-notice subtitle, metadata grid,
 *       and Export Excel / Export PDF buttons (via {@link HtmlPageBuilder})</li>
 *   <li>Sidebar ({@code .sidebar}) — vertical sticky navigation; one button per
 *       section (via {@link HtmlPageBuilder})</li>
 *   <li>Content area ({@code .content-area}) — one {@code .panel} per section;
 *       only the active panel is displayed (via {@link HtmlPageBuilder})</li>
 *   <li>Transaction Metrics table — built here, rendered as a dedicated panel</li>
 *   <li>Performance Charts — Chart.js 2-column grid; inserted before Verdict
 *       (via {@link HtmlPageBuilder})</li>
 * </ol>
 *
 * <p>{@link #buildTransactionMetricsSection(List)} and {@link #escapeHtml(String)}
 * are package-accessible for unit testing without touching the file system.</p>
 *
 * @since 4.6.0
 */
public class HtmlReportRenderer {

    /**
     * Index of the configurable-percentile column in {@link #TABLE_HEADERS}.
     */
    static final int PERCENTILE_COL_INDEX = 7;

    /**
     * Column headers for the Transaction Metrics table.
     * Index {@value #PERCENTILE_COL_INDEX} (\"90th Pct (ms)\") matches the default percentile.
     * {@link #buildTableHeaders(int)} clones this array and updates index
     * {@value #PERCENTILE_COL_INDEX} when a different percentile is configured.
     */
    static final String[] TABLE_HEADERS = {
            "Transaction", "Count", "Passed", "Failed",
            "Avg RT (ms)", "Min RT (ms)", "Max RT (ms)", "90th Pct (ms)",
            "Std Dev", "Error Rate", "TPS", "Received KB/Sec", "Avg Bytes"
    };
    static final String TD_CLOSE = "</td>";
    private static final Logger log = LoggerFactory.getLogger(HtmlReportRenderer.class);
    /**
     * Minimum free disk space required before writing a report.
     * 10 MB is a conservative floor sized to accommodate typical JMeter report
     * output (50–200 KB) with ample headroom.
     */
    private static final long MIN_FREE_BYTES = 10L * 1024 * 1024; // 10 MB

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a {@code <td>} cell showing PASS, FAIL, or - for an SLA check.
     *
     * @param observed   observed numeric value (error % or ms)
     * @param threshold  SLA threshold; ignored when {@code configured} is false
     * @param configured whether the SLA is active
     * @return HTML {@code <td>} element string
     */
    private static String buildSlaCell(double observed, double threshold, boolean configured) {
        if (!configured) {
            return "<td class=\"sla-na\">-" + TD_CLOSE;
        }
        boolean breach = observed > threshold;
        return breach
                ? "<td class=\"sla-fail\">FAIL" + TD_CLOSE
                : "<td class=\"sla-pass\">PASS" + TD_CLOSE;
    }

    // ─────────────────────────────────────────────────────────────
    // Page assembly
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the cell string at {@code index}, or "" if out of bounds or null.
     */
    private static String safeCell(String[] row, int index) {
        return (row != null && index < row.length && row[index] != null) ? row[index] : "";
    }

    // ─────────────────────────────────────────────────────────────
    // Transaction Metrics section (package-private for unit tests)
    // ─────────────────────────────────────────────────────────────

    /**
     * Parses an error-rate cell value (e.g. {@code "1.23%"}) to a double.
     * Returns 0.0 on parse failure.
     */
    static double parseErrorRate(String cell) {
        if (cell == null || cell.isBlank()) return 0.0;
        try {
            return Double.parseDouble(cell.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parses an integer-formatted ms cell (e.g. {@code "312"}) to a double.
     * Returns 0.0 on parse failure.
     */
    static double parseMs(String cell) {
        if (cell == null || cell.isBlank()) return 0.0;
        try {
            return Double.parseDouble(cell.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Formats a threshold value for the column header.
     * Returns an integer string when the value is a whole number (e.g. {@code "2"}),
     * otherwise one decimal place (e.g. {@code "2.5"}).
     */
    private static String formatThreshold(double value) {
        return (value == Math.floor(value)) ? String.valueOf((long) value)
                : String.format("%.1f", value);
    }

    // ─────────────────────────────────────────────────────────────
    // SLA cell helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Escapes {@code &}, {@code <}, and {@code >} for safe HTML embedding.
     *
     * @param s input string (may be null)
     * @return escaped string, or empty string for null input
     */
    static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String[] buildTableHeaders(int percentile) {
        String[] headers = TABLE_HEADERS.clone();
        headers[PERCENTILE_COL_INDEX] = percentile + "th Pct (ms)";
        return headers;
    }

    /**
     * Builds a compact HTML table showing the top error types by frequency.
     * Returns empty string when the error list is empty.
     */
    static String buildErrorBreakdownHtml(List<Map<String, Object>> errorTypeSummary) {
        if (errorTypeSummary == null || errorTypeSummary.isEmpty()) return "";

        long totalErrors = errorTypeSummary.stream()
                .mapToLong(e -> ((Number) e.getOrDefault("count", 0L)).longValue())
                .sum();
        if (totalErrors == 0) return "";

        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"error-breakdown\">\n")
                .append("  <h3>Error Breakdown</h3>\n")
                .append("  <table class=\"data-table\">\n")
                .append("    <thead><tr>\n")
                .append("      <th>Status Code</th><th>Message</th><th>Count</th><th>% of Errors</th>\n")
                .append("    </tr></thead>\n")
                .append("    <tbody>\n");

        for (Map<String, Object> entry : errorTypeSummary) {
            String code = escapeHtml(String.valueOf(entry.getOrDefault("responseCode", "")));
            String msg = escapeHtml(String.valueOf(entry.getOrDefault("responseMessage", "")));
            long count = ((Number) entry.getOrDefault("count", 0L)).longValue();
            double pct = totalErrors > 0 ? (double) count / totalErrors * 100.0 : 0.0;
            sb.append("    <tr>")
                    .append("<td>").append(code).append(TD_CLOSE)
                    .append("<td>").append(msg).append(TD_CLOSE)
                    .append("<td class=\"num\">").append(count).append(TD_CLOSE)
                    .append("<td class=\"num\">").append(String.format("%.1f%%", pct)).append(TD_CLOSE)
                    .append("</tr>\n");
        }

        sb.append("    </tbody>\n")
                .append("  </table>\n")
                .append("</div>\n");
        return sb.toString();
    }

    /**
     * Builds a 3-card KPI panel showing Avg Latency, Avg Connect, and
     * Avg Server Processing time with informational tooltips.
     * Returns empty string when latency data is absent.
     */
    static String buildLatencyPanelHtml(long avgLatencyMs, long avgConnectMs,
                                        boolean latencyPresent) {
        if (!latencyPresent) return "";

        long serverProcessingMs = Math.max(0, avgLatencyMs - avgConnectMs);

        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"latency-panel\">\n")
                .append("  <h3>Network &amp; Server Timing</h3>\n")
                .append("  <div class=\"latency-cards\">\n");

        appendLatencyCard(sb, "Avg Latency (TTFB)", avgLatencyMs,
                "Time to First Byte — time from request sent to first byte received. "
                        + "Includes DNS, TCP, TLS handshake, and server processing. "
                        + "High values indicate network or server-side delays.");

        appendLatencyCard(sb, "Avg Connect Time", avgConnectMs,
                "Time to establish TCP connection (includes DNS + TLS if applicable). "
                        + "High values suggest network latency, DNS resolution issues, "
                        + "or TLS negotiation overhead.");

        appendLatencyCard(sb, "Avg Server Processing", serverProcessingMs,
                "Latency minus Connect — time the server spent processing the request "
                        + "after connection was established. High values point to slow backend "
                        + "logic, database queries, or resource contention.");

        sb.append("  </div>\n")
                .append("</div>\n");
        return sb.toString();
    }

    private static void appendLatencyCard(StringBuilder sb, String label, long valueMs,
                                          String tooltip) {
        sb.append("    <div class=\"latency-card\" title=\"")
                .append(escapeHtml(tooltip)).append("\">\n")
                .append("      <div class=\"latency-label\">").append(escapeHtml(label))
                .append(" <span class=\"info-icon\">\u24D8</span></div>\n")
                .append("      <div class=\"latency-value\">").append(valueMs).append(" ms</div>\n")
                .append("    </div>\n");
    }

    // ─────────────────────────────────────────────────────────────
    // Utility (package-private for HtmlPageBuilder and tests)
    // ─────────────────────────────────────────────────────────────

    /**
     * Renders the full AI report to an HTML file at the specified output path.
     * No Swing dialog is shown — suitable for headless / CLI invocation.
     *
     * @param markdownContent AI-generated report in Markdown; must not be null
     * @param outputPath      absolute path of the output HTML file; must not be null
     * @param config          scenario metadata; must not be null
     * @param tableRows       visible table rows from the plugin (TOTAL excluded)
     * @param timeBuckets     time buckets from the JTL parser
     * @param verdict         extracted AI verdict ("PASS", "FAIL", "UNDECISIVE"); may be null
     * @return absolute path of the written HTML file (same as {@code outputPath})
     * @throws IOException if the file cannot be written
     */
    public String renderToFile(String markdownContent,
                               String outputPath,
                               RenderConfig config,
                               List<String[]> tableRows,
                               List<JTLParser.TimeBucket> timeBuckets,
                               String verdict) throws IOException {
        Objects.requireNonNull(markdownContent, "markdownContent must not be null");
        Objects.requireNonNull(outputPath, "outputPath must not be null");
        Objects.requireNonNull(config, "config must not be null");

        log.info("renderToFile: generating HTML report. outputPath={}", outputPath);

        String safeVerdict = verdict != null ? verdict : "UNDECISIVE";
        String page = buildFullPage(markdownContent, config, tableRows, timeBuckets, safeVerdict);
        writeReport(page, Path.of(outputPath));
        log.info("renderToFile: HTML report written. outputPath={}", outputPath);
        return outputPath;
    }

    // ─────────────────────────────────────────────────────────────
    // Path helpers
    // ─────────────────────────────────────────────────────────────

    private String buildFullPage(String markdownContent, RenderConfig config,
                                 List<String[]> tableRows,
                                 List<JTLParser.TimeBucket> timeBuckets,
                                 String verdict) {
        String htmlBody = HtmlPageBuilder.markdownToHtml(markdownContent);
        String metricsTable = buildTransactionMetricsSection(
                tableRows, config.percentile,
                config.errorSlaThreshold, config.rtSlaThresholdMs, config.rtSlaMetric);
        String chartsBlock = HtmlPageBuilder.buildChartsSection(timeBuckets);
        String errorBreakdown = buildErrorBreakdownHtml(config.errorTypeSummary);
        String latencyPanel = buildLatencyPanelHtml(
                config.avgLatencyMs, config.avgConnectMs, config.latencyPresent);
        return HtmlPageBuilder.buildPage(htmlBody, metricsTable, chartsBlock, config,
                errorBreakdown, latencyPanel, verdict);
    }

    // ─────────────────────────────────────────────────────────────
    // Error breakdown table (Java-computed, prepended to Error Analysis)
    // ─────────────────────────────────────────────────────────────

    /**
     * Convenience overload used by tests — defaults percentile to 90.
     *
     * @param rows data rows (TOTAL excluded)
     * @return HTML string for the metrics section, or empty string if rows is empty
     */
    String buildTransactionMetricsSection(List<String[]> rows) {
        return buildTransactionMetricsSection(rows, 90);
    }

    // ─────────────────────────────────────────────────────────────
    // Latency breakdown panel (Java-computed, prepended to Diagnostics)
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the Transaction Metrics HTML section.
     * Returns an empty string when {@code rows} is {@code null} or empty.
     *
     * @param rows       data rows to render
     * @param percentile configurable percentile label
     * @return HTML section string
     */
    String buildTransactionMetricsSection(List<String[]> rows, int percentile) {
        return buildTransactionMetricsSection(rows, percentile,
                -1, -1, "pnn");
    }

    /**
     * Builds the Transaction Metrics HTML section with optional SLA status columns.
     *
     * <p>When at least one SLA threshold is configured ({@code > -1}), two extra
     * columns are appended — one for error-rate SLA status and one for response-time
     * SLA status. Each cell shows {@code PASS} (green) or {@code FAIL} (red). When
     * only one threshold is configured, the unconfigured column shows {@code -}.</p>
     *
     * <p>When <em>neither</em> SLA is configured, both SLA columns are hidden entirely
     * and a footnote is appended after the table: "SLA Columns Hidden — No SLA
     * Thresholds Were Configured For This Run."</p>
     *
     * <p>All comparisons are performed here in Java — no model arithmetic is involved.</p>
     *
     * @param rows              data rows to render (TOTAL excluded)
     * @param percentile        configured percentile — drives the Nth Pct column header
     * @param errorSlaThreshold error-rate SLA threshold (%); -1 = not configured
     * @param rtSlaThresholdMs  response-time SLA threshold (ms); -1 = not configured
     * @param rtSlaMetric       {@code "avg"} = compare Avg RT; {@code "pnn"} = compare Nth Pct RT
     * @return HTML section string; empty string when rows is null or empty
     */
    String buildTransactionMetricsSection(List<String[]> rows, int percentile,
                                          double errorSlaThreshold, long rtSlaThresholdMs,
                                          String rtSlaMetric) {
        if (rows == null || rows.isEmpty()) return "";

        boolean hasErrorSla = errorSlaThreshold >= 0;
        boolean hasRtSla = rtSlaThresholdMs >= 0;
        boolean hasSla = hasErrorSla || hasRtSla;

        // ── Column headers ────────────────────────────────────────────────────
        String[] baseHeaders = buildTableHeaders(percentile);
        // Error SLA column header: "Error% SLA (≤N%)" or "Error% SLA"
        boolean useAvg = "avg".equalsIgnoreCase(rtSlaMetric);
        String errorSlaHeader = hasErrorSla
                ? "Error% SLA (\u2264" + formatThreshold(errorSlaThreshold) + "%)"
                : "Error% SLA";
        // RT SLA column header reflects the configured metric and threshold
        String rtSlaHeader = hasRtSla
                ? (useAvg ? "Avg" : "P" + percentile) + " RT SLA (\u2264" + rtSlaThresholdMs + " ms)"
                : (useAvg ? "Avg" : "P" + percentile) + " RT SLA";

        StringBuilder sb = new StringBuilder(512 + rows.size() * 200);
        sb.append("<div class=\"metrics-section\">\n")
                .append("  <h2>Transaction Metrics</h2>\n")
                .append("  <div class=\"metrics-toolbar\">\n")
                .append("    <input type=\"text\" id=\"metricsSearch\" placeholder=\"Search transaction names\u2026\">\n")
                .append("    <div class=\"metrics-toolbar-right\">\n")
                .append("      <label>Show\n")
                .append("        <select id=\"metricsPageSize\">\n")
                .append("          <option value=\"10\" selected>10</option>\n")
                .append("          <option value=\"25\">25</option>\n")
                .append("          <option value=\"50\">50</option>\n")
                .append("          <option value=\"100\">100</option>\n")
                .append("        </select> entries\n")
                .append("      </label>\n")
                .append("    </div>\n")
                .append("  </div>\n")
                .append("  <div class=\"sort-hint\" id=\"sortHint\">Click column headers to sort</div>\n")
                .append("  <div class=\"tbl-wrap\">\n")
                .append("  <table id=\"metricsTable\">\n")
                .append("    <thead><tr>\n");

        for (String h : baseHeaders) {
            sb.append("      <th>").append(escapeHtml(h)).append("</th>\n");
        }
        if (hasSla) {
            sb.append("      <th>").append(escapeHtml(errorSlaHeader)).append("</th>\n");
            sb.append("      <th>").append(escapeHtml(rtSlaHeader)).append("</th>\n");
        }
        sb.append("    </tr></thead>\n").append("    <tbody>\n");

        // ── Data rows ─────────────────────────────────────────────────────────
        // Column indices in buildRowAsStrings output:
        //   0=label, 1=count, 2=passed, 3=failed,
        //   4=avg(ms), 5=min(ms), 6=max(ms), 7=Nth pct(ms),
        //   8=stdDev, 9=errorRate%, 10=TPS, 11=Received KB/Sec, 12=avgBytes
        final int ERROR_RATE_COL = 9;
        final int AVG_RT_COL = 4;
        final int PNN_RT_COL = 7;

        for (String[] row : rows) {
            sb.append("    <tr>\n");
            // Existing columns
            for (int i = 0; i < baseHeaders.length; i++) {
                String cell = (row != null && i < row.length && row[i] != null) ? row[i] : "";
                String align = (i == 0) ? "" : " class=\"num\"";
                sb.append("      <td").append(align).append(">")
                        .append(escapeHtml(cell))
                        .append(TD_CLOSE).append("\n");
            }
            if (hasSla) {
                // Error SLA column
                sb.append("      ").append(buildSlaCell(
                        parseErrorRate(safeCell(row, ERROR_RATE_COL)),
                        errorSlaThreshold, hasErrorSla)).append("\n");
                // RT SLA column
                int rtCol = useAvg ? AVG_RT_COL : PNN_RT_COL;
                sb.append("      ").append(buildSlaCell(
                        parseMs(safeCell(row, rtCol)),
                        rtSlaThresholdMs, hasRtSla)).append("\n");
            }

            sb.append("    </tr>\n");
        }
        sb.append("    </tbody>\n").append("  </table>\n").append("  </div>\n");
        sb.append("  <div class=\"metrics-paging\">\n")
                .append("    <span id=\"metricsInfo\"></span>\n")
                .append("    <div id=\"metricsPages\"></div>\n")
                .append("  </div>\n");
        if (!hasSla) {
            sb.append("  <p style=\"font-size:11px;color:var(--color-text-tertiary)\">")
                    .append("SLA Columns Hidden \u2014 No SLA Thresholds Were Configured For This Run.")
                    .append("</p>\n");
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Path helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Writes {@code content} to {@code finalPath} using the mandatory atomic-write pattern:
     * disk-space check → write to sibling {@code .tmp} → ATOMIC_MOVE to final path
     * → guaranteed {@code .tmp} cleanup in {@code finally}.
     *
     * @param content   the HTML content to write; must not be null
     * @param finalPath the destination path (parent directory must be determinable)
     * @throws IOException if disk space is insufficient or the write/rename fails
     */
    private void writeReport(String content, Path finalPath) throws IOException {
        // Step 1: null-safe parent resolution
        Path parentDir = Objects.requireNonNull(
                finalPath.toAbsolutePath().getParent(),
                "finalPath must include a parent directory: " + finalPath);

        // Step 2: ensure parent directory exists, then check disk space.
        // Files.getFileStore() throws NoSuchFileException if parentDir does not exist —
        // createDirectories() is required BEFORE the space check, not after.
        Files.createDirectories(parentDir);
        long usable = Files.getFileStore(parentDir).getUsableSpace();
        if (usable < MIN_FREE_BYTES) {
            throw new IOException(String.format(
                    "Insufficient disk space — %.1f MB available, 10 MB required on %s",
                    usable / (1024.0 * 1024.0), parentDir));
        }

        // Step 3: write to sibling .tmp — same filesystem as finalPath, required for ATOMIC_MOVE
        Path tmpPath = finalPath.resolveSibling(finalPath.getFileName() + ".tmp");
        try {
            Files.writeString(tmpPath, content, StandardCharsets.UTF_8);

            // Step 4: atomic rename to final path
            try {
                Files.move(tmpPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // DESIGN: ATOMIC_MOVE can fail on non-NTFS filesystems (FAT32, exFAT),
                // network/CIFS shares, or some containerised volumes that do not support
                // atomic rename at the OS level. REPLACE_EXISTING is non-atomic but safe
                // for single-JVM use in these environments.
                Files.move(tmpPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Step 5: always delete .tmp — whether write succeeded, rename succeeded, or both failed.
            Files.deleteIfExists(tmpPath);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // RenderConfig value object
    // ─────────────────────────────────────────────────────────────

    /**
     * Immutable value object grouping all report metadata.
     */
    public static final class RenderConfig {
        /**
         * Virtual user count label.
         */
        public final String users;
        /**
         * Test plan name.
         */
        public final String scenarioName;
        /**
         * Test plan description / comment.
         */
        public final String scenarioDesc;
        /**
         * First thread group name.
         */
        public final String threadGroupName;
        /**
         * Formatted test start time.
         */
        public final String startTime;
        /**
         * Formatted test end time.
         */
        public final String endTime;
        /**
         * Formatted test duration.
         */
        public final String duration;
        /**
         * Configured percentile (1–99).
         */
        public final int percentile;
        /**
         * Display name of the AI provider used to generate the report (e.g. "Groq (Free)").
         */
        public final String providerDisplayName;
        /**
         * Error-rate SLA threshold (%); -1 means not configured.
         */
        public final double errorSlaThreshold;
        /**
         * Response-time SLA threshold (ms); -1 means not configured.
         */
        public final long rtSlaThresholdMs;
        /**
         * Which RT column the SLA applies to: {@code "avg"} for Avg RT,
         * {@code "pnn"} for Nth-percentile. Ignored when {@link #rtSlaThresholdMs} is -1.
         */
        public final String rtSlaMetric;
        /**
         * Top-5 error types by frequency. Each map contains "responseCode",
         * "responseMessage", and "count". Empty list when no errors occurred.
         */
        public final List<Map<String, Object>> errorTypeSummary;
        /**
         * Average Latency (TTFB) in milliseconds across all samples.
         * Zero when {@link #latencyPresent} is false.
         */
        public final long avgLatencyMs;
        /**
         * Average Connect time in milliseconds across all samples.
         * Zero when {@link #latencyPresent} is false.
         */
        public final long avgConnectMs;
        /**
         * {@code true} when at least one sample had a non-zero Latency value.
         */
        public final boolean latencyPresent;

        /**
         * Constructs a render configuration.
         *
         * @param users               virtual user count label (null → "")
         * @param scenarioName        test plan name (null → "")
         * @param scenarioDesc        test plan description (null → "")
         * @param threadGroupName     first thread group name (null → "")
         * @param startTime           formatted start time (null → "")
         * @param endTime             formatted end time (null → "")
         * @param duration            formatted duration (null → "")
         * @param percentile          configured percentile (1–99)
         * @param providerDisplayName AI provider display name shown in the report footer (null → "")
         * @param errorSlaThreshold   error-rate SLA threshold (%); -1 = not configured
         * @param rtSlaThresholdMs    response-time SLA threshold (ms); -1 = not configured
         * @param rtSlaMetric         {@code "avg"} or {@code "pnn"} — which RT column to check
         * @param errorTypeSummary    top-5 error types (null → empty list)
         * @param avgLatencyMs        average Latency (TTFB) ms; 0 if absent
         * @param avgConnectMs        average Connect ms; 0 if absent
         * @param latencyPresent      true when at least one non-zero Latency value exists
         */
        public RenderConfig(String users, String scenarioName, String scenarioDesc,
                            String threadGroupName, String startTime, String endTime,
                            String duration, int percentile, String providerDisplayName,
                            double errorSlaThreshold, long rtSlaThresholdMs,
                            String rtSlaMetric,
                            List<Map<String, Object>> errorTypeSummary,
                            long avgLatencyMs, long avgConnectMs, boolean latencyPresent) {
            this.users = Objects.requireNonNullElse(users, "");
            this.scenarioName = Objects.requireNonNullElse(scenarioName, "");
            this.scenarioDesc = Objects.requireNonNullElse(scenarioDesc, "");
            this.threadGroupName = Objects.requireNonNullElse(threadGroupName, "");
            this.startTime = Objects.requireNonNullElse(startTime, "");
            this.endTime = Objects.requireNonNullElse(endTime, "");
            this.duration = Objects.requireNonNullElse(duration, "");
            this.percentile = percentile;
            this.providerDisplayName = Objects.requireNonNullElse(providerDisplayName, "");
            this.errorSlaThreshold = errorSlaThreshold;
            this.rtSlaThresholdMs = rtSlaThresholdMs;
            this.rtSlaMetric = Objects.requireNonNullElse(rtSlaMetric, "pnn");
            this.errorTypeSummary = errorTypeSummary != null ? errorTypeSummary : Collections.emptyList();
            this.avgLatencyMs = avgLatencyMs;
            this.avgConnectMs = avgConnectMs;
            this.latencyPresent = latencyPresent;
        }
    }
}