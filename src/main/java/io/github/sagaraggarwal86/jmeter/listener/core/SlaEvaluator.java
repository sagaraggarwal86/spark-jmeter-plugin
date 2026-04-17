package io.github.sagaraggarwal86.jmeter.listener.core;

import java.util.List;

/**
 * Evaluates SLA thresholds against table rows and builds verdict HTML.
 *
 * <p>Shared by both the GUI {@code AiReportLauncher} and the CLI
 * {@code CliReportPipeline} — single source of truth for SLA breach
 * counting, verdict derivation, and verdict panel rendering.</p>
 *
 * <p>All methods are {@code public static} and this class is non-instantiable.</p>
 *
 * @since 5.5.0
 */
public final class SlaEvaluator {

    private static final int TPS_COL = 10;
    private static final int ERROR_RATE_COL = 9;
    private static final int AVG_COL = 4;
    private static final int PNN_COL = 7;

    private SlaEvaluator() {
        throw new AssertionError("SlaEvaluator is a utility class");
    }

    /**
     * Evaluates all configured SLA thresholds in a single pass.
     *
     * @param rows           table rows (TOTAL excluded)
     * @param tpsThreshold   minimum TPS; -1 = disabled
     * @param errorThreshold maximum error %; -1 = disabled
     * @param rtThresholdMs  maximum RT ms; -1 = disabled
     * @param useAvg         {@code true} to compare Avg RT, {@code false} for Pnn RT
     * @return evaluation result with per-threshold breach counts
     */
    public static SlaResult evaluate(List<String[]> rows,
                                     double tpsThreshold, double errorThreshold,
                                     long rtThresholdMs, boolean useAvg) {
        int tpsFails = 0, errorFails = 0, rtFails = 0;
        for (String[] row : rows) {
            if (tpsThreshold >= 0
                && CellValueParser.parseTps(safeCell(row, TPS_COL)) < tpsThreshold)
                tpsFails++;
            if (errorThreshold >= 0
                && CellValueParser.parseErrorRate(safeCell(row, ERROR_RATE_COL)) > errorThreshold)
                errorFails++;
            if (rtThresholdMs >= 0) {
                double rt = CellValueParser.parseMs(safeCell(row, useAvg ? AVG_COL : PNN_COL));
                if (rt > rtThresholdMs) rtFails++;
            }
        }
        return new SlaResult(tpsFails, errorFails, rtFails, rows.size());
    }

    /**
     * Builds the SLA Verdict HTML panel from evaluation results.
     *
     * @param result         SLA evaluation result
     * @param tpsThreshold   TPS threshold; -1 = not configured
     * @param errorThreshold error % threshold; -1 = not configured
     * @param rtThresholdMs  RT threshold ms; -1 = not configured
     * @param useAvg         whether RT SLA uses Avg metric
     * @param percentile     configured percentile (for column header label)
     * @return HTML string for the SLA Verdict panel
     */
    public static String buildVerdictHtml(SlaResult result,
                                          double tpsThreshold, double errorThreshold,
                                          long rtThresholdMs, boolean useAvg,
                                          int percentile) {
        String verdictClass = result.totalBreaches() > 0 ? "sla-fail" : "sla-pass";

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>SLA Verdict</h2>\n");
        sb.append("<div class=\"sla-verdict-panel\">\n");
        sb.append("  <div class=\"sla-verdict-badge ").append(verdictClass).append("\">")
            .append(result.verdict()).append("</div>\n");
        sb.append("  <table class=\"data-table\" style=\"margin-top:16px;max-width:500px\">\n");
        sb.append("    <thead><tr><th>SLA Threshold</th><th>Breaches</th><th>Status</th></tr></thead>\n");
        sb.append("    <tbody>\n");

        if (tpsThreshold >= 0)
            appendSlaRow(sb, "TPS (\u2265" + formatThreshold(tpsThreshold) + ")",
                result.tpsFails, result.totalRows);
        if (errorThreshold >= 0)
            appendSlaRow(sb, "Error% (\u2264" + formatThreshold(errorThreshold) + "%)",
                result.errorFails, result.totalRows);
        if (rtThresholdMs >= 0) {
            String label = (useAvg ? "Avg" : "P" + percentile) + " RT (\u2264" + rtThresholdMs + " ms)";
            appendSlaRow(sb, label, result.rtFails, result.totalRows);
        }

        sb.append("    </tbody>\n  </table>\n");
        boolean noSlaConfigured = tpsThreshold < 0 && errorThreshold < 0 && rtThresholdMs < 0;
        if (noSlaConfigured) {
            sb.append("  <p style=\"margin-top:12px;color:var(--color-text-secondary)\">")
                .append("No SLA thresholds configured.</p>\n");
        } else {
            int b = result.totalBreaches();
            sb.append("  <p style=\"margin-top:12px;color:var(--color-text-secondary)\">")
                .append(b).append(" breach").append(b != 1 ? "es" : "")
                .append(" across ").append(result.totalRows).append(" transaction")
                .append(result.totalRows != 1 ? "s" : "").append("</p>\n");
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    private static void appendSlaRow(StringBuilder sb, String label, int fails, int total) {
        String css = fails > 0 ? "sla-fail" : "sla-pass";
        sb.append("    <tr><td>").append(label).append("</td>")
            .append("<td class=\"num\">").append(fails).append("/").append(total).append("</td>")
            .append("<td class=\"").append(css).append("\">")
            .append(fails > 0 ? "FAIL" : "PASS").append("</td></tr>\n");
    }

    private static String formatThreshold(double value) {
        return (value == Math.floor(value)) ? String.valueOf((long) value)
            : String.format("%.1f", value);
    }

    private static String safeCell(String[] row, int index) {
        return (row != null && index < row.length && row[index] != null) ? row[index] : "";
    }

    /**
     * Builds an HTML panel for the classification-based verdict (no SLA thresholds configured).
     *
     * @param verdict        "PASS" or "FAIL"
     * @param classification classification label (e.g. "THROUGHPUT-BOUND", "ERROR-BOUND")
     * @param reasoning      human-readable reasoning string
     * @return HTML string for the Classification Verdict panel
     */
    public static String buildClassificationVerdictHtml(String verdict, String classification,
                                                        String reasoning) {
        String verdictClass = "FAIL".equals(verdict) ? "sla-fail" : "sla-pass";

        StringBuilder sb = new StringBuilder();
        sb.append("<h2>Classification Verdict</h2>\n");
        sb.append("<div class=\"sla-verdict-panel\">\n");
        sb.append("  <div class=\"sla-verdict-badge ").append(verdictClass).append("\">")
            .append(verdict).append("</div>\n");
        sb.append("  <table class=\"data-table\" style=\"margin-top:16px;max-width:600px\">\n");
        sb.append("    <thead><tr><th>Property</th><th>Value</th></tr></thead>\n");
        sb.append("    <tbody>\n");
        sb.append("    <tr><td>Classification</td><td><strong>").append(escapeHtml(classification))
            .append("</strong></td></tr>\n");
        sb.append("    <tr><td>Reasoning</td><td>").append(escapeHtml(reasoning))
            .append("</td></tr>\n");
        sb.append("    <tr><td>Verdict Source</td><td>CLASSIFICATION</td></tr>\n");
        sb.append("    </tbody>\n  </table>\n");
        sb.append("  <p style=\"margin-top:12px;color:var(--color-text-secondary)\">")
            .append("No SLA thresholds configured — verdict derived from workload classification.</p>\n");
        sb.append("</div>\n");
        return sb.toString();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * Result of a single-pass SLA evaluation across all table rows.
     *
     * @param tpsFails   number of rows breaching TPS SLA
     * @param errorFails number of rows breaching error % SLA
     * @param rtFails    number of rows breaching response time SLA
     * @param totalRows  total number of rows evaluated
     */
    public record SlaResult(int tpsFails, int errorFails, int rtFails, int totalRows) {

        public int totalBreaches() {
            return tpsFails + errorFails + rtFails;
        }

        public String verdict() {
            return totalBreaches() > 0 ? "FAIL" : "PASS";
        }
    }
}
