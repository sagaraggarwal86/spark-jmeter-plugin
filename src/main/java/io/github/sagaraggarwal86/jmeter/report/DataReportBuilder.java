package io.github.sagaraggarwal86.jmeter.report;

import io.github.sagaraggarwal86.jmeter.ai.report.HtmlReportRenderer;
import io.github.sagaraggarwal86.jmeter.listener.core.CellValueParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds HTML section content for data-only reports (no AI provider).
 *
 * <p>Pure static methods producing titled HTML fragments suitable for tabbed
 * display in {@link HtmlReportRenderer#renderDataReport}. Each section
 * contains an {@code <h2>} heading so the page builder can derive tab titles.</p>
 *
 * <p>No I/O, no Swing, no network — fully unit-testable.</p>
 *
 * @since 5.6.0
 */
public final class DataReportBuilder {

    private DataReportBuilder() { /* static utility */ }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the variable content sections for a data-only report.
     *
     * <p>The returned sections are inserted into the report alongside
     * fixed sections (Transaction Metrics, Error Analysis, Network Timing,
     * Performance Charts) that are built by the renderer from existing
     * shared methods.</p>
     *
     * @param classification classification map ({@code label}, {@code reasoning}); null if not computed
     * @param globalStats    global statistics map; null if not computed
     * @param slaVerdictHtml pre-built SLA verdict HTML from {@code SlaEvaluator}; null if no SLAs configured
     * @param tableRows      data rows (13-column arrays from {@code TablePopulator})
     * @param percentile     configured percentile (1–99)
     * @param rtMetric       {@code "avg"} or {@code "pnn"}
     * @return mutable list of {@code [title, htmlContent]} section pairs
     */
    public static List<String[]> buildSections(
        Map<String, Object> classification,
        Map<String, Object> globalStats,
        String slaVerdictHtml,
        List<String[]> tableRows,
        int percentile,
        String rtMetric) {

        List<String[]> sections = new ArrayList<>();

        // 1. Workload Classification — present when classification was computed
        if (classification != null && classification.containsKey("label")) {
            sections.add(new String[]{"Workload Classification",
                buildClassificationSection(classification, globalStats)});
        }

        // 2. SLA Evaluation — present when SLAs were configured
        //    (buildVerdictHtml already contains its own <h2>SLA Verdict</h2>)
        if (slaVerdictHtml != null && !slaVerdictHtml.isBlank()) {
            sections.add(new String[]{"SLA Evaluation", slaVerdictHtml});
        }

        // 3. Slowest Endpoints — present when table rows exist
        if (tableRows != null && !tableRows.isEmpty()) {
            sections.add(new String[]{"Slowest Endpoints",
                buildSlowestEndpointsSection(tableRows, percentile, rtMetric)});
        }

        return sections;
    }

    // ─────────────────────────────────────────────────────────────
    // Section builders (package-private for testing)
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the Workload Classification section — badge, reasoning, and key metrics.
     */
    static String buildClassificationSection(Map<String, Object> classification,
                                             Map<String, Object> globalStats) {
        String label = String.valueOf(classification.getOrDefault("label", "UNKNOWN"));
        String reasoning = String.valueOf(classification.getOrDefault("reasoning", ""));

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<h2>Workload Classification</h2>\n")
            .append("<div class=\"classification-section\">\n");

        // Classification badge — colour-coded by severity
        String badgeClass = switch (label) {
            case "ERROR-BOUND", "CAPACITY-WALL" -> "badge-fail";
            case "LATENCY-BOUND" -> "badge-warn";
            default -> "badge-pass"; // THROUGHPUT-BOUND or unknown
        };
        sb.append("  <div class=\"classification-badge ").append(badgeClass).append("\">")
            .append(HtmlReportRenderer.escapeHtml(label)).append("</div>\n");

        // Human-readable analysis
        String description = humanizeClassification(label);
        sb.append("  <div class=\"classification-reasoning\">\n")
            .append("    <h3>Analysis</h3>\n")
            .append("    <p>").append(HtmlReportRenderer.escapeHtml(description)).append("</p>\n")
            .append("  </div>\n");

        // Classification decision factors — show how close to thresholds
        sb.append("  <div class=\"classification-metrics\">\n")
            .append("    <h3>Classification Factors</h3>\n")
            .append("    <table class=\"data-table\">\n")
            .append("      <thead><tr><th>Factor</th><th>Value</th><th>Threshold</th><th>Status</th></tr></thead>\n")
            .append("      <tbody>\n");

        double errorPct = toDouble(classification.get("errorRatePct"),
            globalStats != null ? toDouble(globalStats.get("errorRatePct"), 0) : 0);
        appendThresholdRow(sb, "Error Rate", String.format("%.2f%%", errorPct), "> 2.0%",
            errorPct > 2.0);

        Object latRatioObj = classification.get("latencyRatio");
        if (latRatioObj instanceof Number n) {
            appendThresholdRow(sb, "Latency Ratio (P99/Avg)", String.format("%.2f", n.doubleValue()),
                "> 3.0", n.doubleValue() > 3.0);
        }

        Object platObj = classification.get("plateauRatio");
        if (platObj instanceof Number n) {
            appendThresholdRow(sb, "TPS Plateau Ratio", String.format("%.2f", n.doubleValue()),
                "\u2265 0.90", n.doubleValue() >= 0.90);
        }

        sb.append("      </tbody>\n")
            .append("    </table>\n")
            .append("  </div>\n");

        // Key metrics from globalStats
        if (globalStats != null && !globalStats.isEmpty()) {
            sb.append("  <div class=\"classification-metrics\">\n")
                .append("    <h3>Key Metrics</h3>\n")
                .append("    <table class=\"data-table\">\n")
                .append("      <thead><tr><th>Metric</th><th>Value</th></tr></thead>\n")
                .append("      <tbody>\n");

            appendMetricRow(sb, "Avg Response Time", globalStats, "avgResponseMs", " ms");
            appendMetricRow(sb, "P99 Response Time", globalStats, "p99ResponseMs", " ms");
            appendMetricRow(sb, "Error Rate", globalStats, "errorRatePct", "%");
            appendMetricRow(sb, "Total Requests", globalStats, "totalRequests", "");
            appendMetricRow(sb, "Throughput (TPS)", globalStats, "throughputTPS", "/sec");

            sb.append("      </tbody>\n")
                .append("    </table>\n")
                .append("  </div>\n");
        }

        sb.append("</div>\n");
        return sb.toString();
    }

    /**
     * Builds an HTML detail list of transactions that breached each SLA threshold.
     * Appended to the SLA verdict panel to show exactly which endpoints failed.
     *
     * @param tableRows data rows (TOTAL excluded)
     * @param tpsSla    TPS threshold (min); -1 = disabled
     * @param errorSla  error % threshold (max); -1 = disabled
     * @param rtSla     RT threshold in ms (max); -1 = disabled
     * @param rtMetric  {@code "avg"} or {@code "pnn"}
     * @return HTML string; empty if no SLAs configured or no breaches
     */
    public static String buildSlaBreachDetails(List<String[]> tableRows,
                                               double tpsSla, double errorSla,
                                               long rtSla, String rtMetric) {
        if (tableRows == null || tableRows.isEmpty()) return "";
        boolean hasTps = tpsSla >= 0;
        boolean hasError = errorSla >= 0;
        boolean hasRt = rtSla >= 0;
        if (!hasTps && !hasError && !hasRt) return "";

        boolean useAvg = "avg".equalsIgnoreCase(rtMetric);
        int rtCol = useAvg ? 4 : 7;

        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"sla-breach-details\" style=\"margin-top:16px\">\n")
            .append("  <h3>Breached Transactions</h3>\n");

        if (hasTps) appendBreachList(sb, "TPS", tableRows, 10, tpsSla, true);
        if (hasError) appendBreachList(sb, "Error%", tableRows, 9, errorSla, false);
        if (hasRt) appendBreachList(sb, "RT", tableRows, rtCol, rtSla, false);

        sb.append("</div>\n");
        return sb.toString();
    }

    private static void appendBreachList(StringBuilder sb, String slaName,
                                         List<String[]> rows, int col,
                                         double threshold, boolean breachWhenBelow) {
        List<String> breached = new ArrayList<>();
        for (String[] row : rows) {
            String cellStr = safeCell(row, col);
            double val = col == 10 ? CellValueParser.parseTps(cellStr)
                : col == 9 ? CellValueParser.parseErrorRate(cellStr)
                  : CellValueParser.parseMs(cellStr);
            boolean breach = breachWhenBelow ? val < threshold : val > threshold;
            if (breach) {
                breached.add(safeCell(row, 0) + " (" + cellStr.trim() + ")");
            }
        }
        if (breached.isEmpty()) return;

        sb.append("  <p style=\"margin:8px 0 4px;font-weight:600;font-size:12px;color:var(--color-text-secondary)\">")
            .append(HtmlReportRenderer.escapeHtml(slaName))
            .append(" \u2014 ").append(breached.size()).append(" breach")
            .append(breached.size() == 1 ? "" : "es").append(":</p>\n")
            .append("  <p style=\"font-size:12px;color:var(--color-text-tertiary);line-height:1.6\">");
        int shown = Math.min(breached.size(), 10);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(HtmlReportRenderer.escapeHtml(breached.get(i)));
        }
        if (breached.size() > 10) {
            sb.append(" and ").append(breached.size() - 10).append(" more");
        }
        sb.append("</p>\n");
    }

    /**
     * Builds the Slowest Endpoints section — top 5 transactions sorted by response time.
     *
     * @param rows       data rows (TOTAL excluded)
     * @param percentile configured percentile for column header
     * @param rtMetric   {@code "avg"} or {@code "pnn"}
     */
    static String buildSlowestEndpointsSection(List<String[]> rows, int percentile,
                                               String rtMetric) {
        boolean useAvg = "avg".equalsIgnoreCase(rtMetric);
        int rtCol = useAvg ? 4 : 7; // 4=avg, 7=pnn
        String rtLabel = useAvg ? "Avg RT (ms)" : "P" + percentile + " RT (ms)";

        // Sort by RT descending, take top 5
        List<String[]> sorted = rows.stream()
            .sorted((a, b) -> Double.compare(CellValueParser.parseMs(safeCell(b, rtCol)),
                CellValueParser.parseMs(safeCell(a, rtCol))))
            .limit(5)
            .toList();

        StringBuilder sb = new StringBuilder(512);
        sb.append("<h2>Slowest Endpoints</h2>\n")
            .append("<div class=\"slowest-section\">\n")
            .append("  <table class=\"data-table\">\n")
            .append("    <thead><tr>\n")
            .append("      <th>#</th><th>Transaction</th><th>")
            .append(HtmlReportRenderer.escapeHtml(rtLabel))
            .append("</th><th>Count</th><th>Error Rate</th><th>TPS</th>\n")
            .append("    </tr></thead>\n")
            .append("    <tbody>\n");

        int rank = 1;
        for (String[] row : sorted) {
            sb.append("    <tr>")
                .append("<td class=\"num\">").append(rank++).append("</td>")
                .append("<td>").append(HtmlReportRenderer.escapeHtml(safeCell(row, 0))).append("</td>")
                .append("<td class=\"num\">").append(HtmlReportRenderer.escapeHtml(safeCell(row, rtCol))).append("</td>")
                .append("<td class=\"num\">").append(HtmlReportRenderer.escapeHtml(safeCell(row, 1))).append("</td>")
                .append("<td class=\"num\">").append(HtmlReportRenderer.escapeHtml(safeCell(row, 9))).append("</td>")
                .append("<td class=\"num\">").append(HtmlReportRenderer.escapeHtml(safeCell(row, 10))).append("</td>")
                .append("</tr>\n");
        }

        sb.append("    </tbody>\n")
            .append("  </table>\n")
            .append("</div>\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private static String humanizeClassification(String label) {
        return switch (label) {
            case "THROUGHPUT-BOUND" -> "Healthy workload \u2014 TPS is stable with acceptable latency and error rates. "
                + "The system is operating within its throughput capacity.";
            case "ERROR-BOUND" -> "Error-bound workload \u2014 error rate exceeds the 2% threshold, "
                + "indicating reliability issues that need investigation.";
            case "CAPACITY-WALL" -> "Capacity wall detected \u2014 TPS has plateaued while latency is growing, "
                + "suggesting the system has reached its maximum throughput capacity.";
            case "LATENCY-BOUND" -> "Latency-bound workload \u2014 response times are disproportionately high "
                + "relative to averages, indicating server-side or network bottlenecks.";
            default -> "Workload classification: " + label + ".";
        };
    }

    private static void appendMetricRow(StringBuilder sb, String label,
                                        Map<String, Object> stats, String key, String suffix) {
        Object val = stats.get(key);
        if (val == null) return;
        String formatted;
        if (val instanceof Number n) {
            double d = n.doubleValue();
            formatted = (d == Math.floor(d) && !Double.isInfinite(d))
                ? n.longValue() + suffix
                : String.format("%.2f%s", d, suffix);
        } else {
            formatted = val + suffix;
        }
        sb.append("      <tr><td>").append(HtmlReportRenderer.escapeHtml(label))
            .append("</td><td class=\"num\">").append(HtmlReportRenderer.escapeHtml(formatted))
            .append("</td></tr>\n");
    }

    private static void appendThresholdRow(StringBuilder sb, String factor, String value,
                                           String threshold, boolean breached) {
        sb.append("      <tr><td>").append(HtmlReportRenderer.escapeHtml(factor))
            .append("</td><td class=\"num\">").append(HtmlReportRenderer.escapeHtml(value))
            .append("</td><td class=\"num\">").append(HtmlReportRenderer.escapeHtml(threshold))
            .append("</td><td class=\"").append(breached ? "sla-fail" : "sla-pass")
            .append("\">").append(breached ? "BREACHED" : "OK")
            .append("</td></tr>\n");
    }

    private static double toDouble(Object obj, double fallback) {
        return obj instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String safeCell(String[] row, int index) {
        return (row != null && index < row.length && row[index] != null) ? row[index] : "";
    }
}
