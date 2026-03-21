package com.personal.jmeter.ai.report;

import com.personal.jmeter.parser.JTLParser;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assembles the complete HTML report page from its constituent parts.
 *
 * <p>Extracted from {@link HtmlReportRenderer} to satisfy the 300-line class
 * design limit (Standard 3 SRP). Responsibility: pure HTML/JS string generation —
 * no file I/O, no network access.</p>
 *
 * <p>The report is rendered as a single self-contained HTML file with:</p>
 * <ul>
 *   <li>Up to 9 clickable tabs — 7 AI analysis sections, Transaction Metrics,
 *       Performance Charts (metrics tab omitted when empty)</li>
 *   <li>Export buttons — "Export Excel" (SheetJS, one sheet per tab) and
 *       "Export PDF" (browser print dialog)</li>
 *   <li>Chart.js initialisation outside the Charts tab panel to avoid the
 *       0×0 canvas rendering that occurs when a canvas is inside a hidden element</li>
 * </ul>
 *
 * <p>All methods are package-private statics; callers do not need an instance.</p>
 */
final class HtmlPageBuilder {

    private static final DateTimeFormatter CHART_TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Pattern for extracting the text content of an {@code <h2>} tag.
     * Used by {@link #splitAtH2(String)} to derive tab titles from section headings.
     * {@code DOTALL} handles multi-line content; inner HTML tags are stripped
     * separately via {@link #HTML_TAG_PATTERN}.
     */
    private static final Pattern H2_TITLE_PATTERN =
            Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Strips all HTML tags to produce a clean plain-text tab label.
     */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private HtmlPageBuilder() { /* static utility — not instantiable */ }

    // ─────────────────────────────────────────────────────────────
    // Page assembly
    // ─────────────────────────────────────────────────────────────

    /**
     * Assembles the full standalone HTML page with tabbed section navigation
     * and Excel / PDF export buttons.
     *
     * <p>Page structure (top to bottom):</p>
     * <ol>
     *   <li>Report header — title and scenario metadata (always visible)</li>
     *   <li>Tab bar — one button per section (sticky, always visible)</li>
     *   <li>Export bar — "Export Excel" and "Export PDF" buttons</li>
     *   <li>Content area — tab panels; only the active panel is displayed</li>
     *   <li>Footer — attribution (always visible)</li>
     * </ol>
     *
     * <p>The Chart.js initialisation {@code <script>} block is placed
     * <em>outside</em> its tab panel so Chart.js instances are created on page
     * load even when the Charts tab is hidden. On Charts-tab activation a resize
     * call corrects the 0×0 canvas dimensions.</p>
     *
     * @param htmlBody     the AI-generated analysis converted from Markdown
     * @param metricsTable the Transaction Metrics HTML section (may be empty)
     * @param chartsBlock  the Performance Charts HTML section (may be empty)
     * @param config       scenario metadata
     * @return complete HTML document as a string
     */
    static String buildPage(String htmlBody, String metricsTable,
                            String chartsBlock, HtmlReportRenderer.RenderConfig config) {

        // ── Metadata header ──────────────────────────────────────────────────
        String runDateTime = buildRunDateTime(config.startTime, config.endTime);
        StringBuilder meta = new StringBuilder("<table class=\"meta-table\">\n");
        appendMetaRow(meta, "Scenario Name", config.scenarioName);
        appendMetaRow(meta, "Scenario Description", config.scenarioDesc);
        appendMetaRow(meta, "Virtual Users", config.users);
        if (!runDateTime.isEmpty()) appendMetaRow(meta, "Run Date/Time", runDateTime);
        appendMetaRow(meta, "Duration", config.duration);
        meta.append("</table>\n");

        // ── Section list ─────────────────────────────────────────────────────
        // 1–7: AI analysis sections split from htmlBody at <h2> boundaries
        List<String[]> sections = splitAtH2(htmlBody != null ? htmlBody : "");

        // 8: Transaction Metrics — only when non-blank
        String safeMetrics = metricsTable != null ? metricsTable : "";
        if (!safeMetrics.isBlank()) {
            sections.add(new String[]{"Transaction Metrics", safeMetrics});
        }

        // 9: Performance Charts — always added.
        // Split the Chart.js <script> block out of the panel so instances are
        // created on page load regardless of which tab is initially visible.
        String[] chartsParts = splitChartsBlock(chartsBlock != null ? chartsBlock : "");
        String chartsContent = chartsParts[0]; // <div class="charts-section">…canvases…</div>
        String chartsScript = chartsParts[1]; // <script>(function(){…})();</script>
        int chartsTabIndex = sections.size();
        sections.add(new String[]{"Performance Charts", chartsContent});

        // ── Tab bar ──────────────────────────────────────────────────────────
        StringBuilder tabBar = new StringBuilder("<nav class=\"tab-bar\" role=\"tablist\">\n");
        for (int i = 0; i < sections.size(); i++) {
            String title = sections.get(i)[0];
            tabBar.append("  <button class=\"tab-btn")
                    .append(i == 0 ? " active" : "")
                    .append("\" role=\"tab\" data-tab=\"").append(i).append("\"")
                    .append(i == chartsTabIndex ? " data-charts=\"true\"" : "")
                    .append(">").append(HtmlReportRenderer.escapeHtml(title))
                    .append("</button>\n");
        }
        tabBar.append("</nav>\n");

        // ── Export bar ───────────────────────────────────────────────────────
        String exportBar = "<div class=\"export-bar\">\n"
                + "  <button onclick=\"exportExcel()\">&#x1F4E5; Export Excel</button>\n"
                + "  <button onclick=\"window.print()\">&#x1F4C4; Export PDF</button>\n"
                + "</div>\n";

        // ── Tab panels ───────────────────────────────────────────────────────
        StringBuilder panels = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            String title = sections.get(i)[0];
            String content = sections.get(i)[1];
            panels.append("<div class=\"tab-panel")
                    .append(i == 0 ? " active" : "")
                    .append("\" id=\"tab-").append(i).append("\"")
                    .append(" data-title=\"").append(HtmlReportRenderer.escapeHtml(title)).append("\">\n")
                    .append(content)
                    .append("\n</div>\n");
        }

        // ── Footer ───────────────────────────────────────────────────────────
        String footer = "  <div class=\"footer\">Generated by JAAR Plugin using "
                + HtmlReportRenderer.escapeHtml(
                config.providerDisplayName.isBlank() ? "AI" : config.providerDisplayName)
                + ". AI analysis may contain errors &mdash; validate results before use."
                + " The author assumes no liability for decisions based on this report.</div>\n";

        return new StringBuilder(12288)
                .append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n")
                .append("  <meta charset=\"UTF-8\">\n")
                .append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n")
                .append("  <title>AI Performance Report</title>\n")
                .append("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.1/chart.umd.min.js\"></script>\n")
                .append("  <script src=\"https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.mini.min.js\"></script>\n")
                .append(buildCss())
                .append(buildMetaScript(config, runDateTime)) // window.jaarMeta for Test Info sheet
                .append("</head>\n<body>\n")
                .append("<div class=\"report-header\">\n")
                .append("  <h1>JMeter AI Performance Report</h1>\n")
                .append("  ").append(meta)
                .append("</div>\n")
                .append(tabBar)
                .append(exportBar)
                .append("<div class=\"content\">\n")
                .append(panels)
                .append(footer)
                .append("</div>\n")
                .append(chartsScript)   // Chart.js init OUTSIDE panels — avoids 0x0 canvas bug
                .append(buildTabJs())   // tab switching + Excel export
                .append("</body>\n</html>\n")
                .toString();
    }

    // ─────────────────────────────────────────────────────────────
    // Section splitting
    // ─────────────────────────────────────────────────────────────

    /**
     * Splits a rendered HTML body into titled sections at {@code <h2>} boundaries.
     *
     * <p>The split uses a lookahead so each fragment retains its opening {@code <h2>}
     * tag — the heading remains inside the tab panel as the visible section title.
     * Any content before the first {@code <h2>} (preamble) is prepended to the
     * first section so no content is silently discarded.</p>
     *
     * <p>If the body contains no {@code <h2>} tags (e.g. a severely truncated AI
     * response), the entire body is returned as a single section titled
     * {@code "Analysis"}.</p>
     *
     * @param htmlBody rendered HTML from {@link #markdownToHtml(String)}; may be null
     * @return mutable list of {@code [title, content]} pairs; never null;
     * empty only when {@code htmlBody} is null or blank
     */
    static List<String[]> splitAtH2(String htmlBody) {
        List<String[]> sections = new ArrayList<>();
        if (htmlBody == null || htmlBody.isBlank()) return sections;

        // Split on <h2> lookahead — each fragment starts with <h2> except possibly
        // the first when there is preamble text before the first heading.
        String[] fragments = htmlBody.split("(?=<h2[^>]*>)", -1);

        String preamble = "";
        for (String fragment : fragments) {
            if (!fragment.toLowerCase(java.util.Locale.ROOT).contains("<h2")) {
                // Text before the first heading — hold as preamble, prepend to first section
                preamble = fragment;
                continue;
            }
            Matcher m = H2_TITLE_PATTERN.matcher(fragment);
            String rawTitle = m.find() ? m.group(1) : "Section";
            // Strip any inner HTML tags (e.g. <strong>, <em>) to produce a clean tab label
            String title = HTML_TAG_PATTERN.matcher(rawTitle).replaceAll("").trim();
            if (title.isEmpty()) title = "Section";

            String content = preamble + fragment;
            preamble = "";
            sections.add(new String[]{title, content});
        }

        // Fallback: no <h2> found — return entire body as one unnamed section
        if (sections.isEmpty()) {
            sections.add(new String[]{"Analysis", htmlBody});
        }
        return sections;
    }

    /**
     * Separates the charts panel HTML from its Chart.js initialisation script.
     *
     * <p>The {@code chartsBlock} produced by {@link #buildChartsSection} contains a
     * {@code <div class="charts-section">} element followed by a {@code <script>}
     * block. Splitting them allows the panel HTML to sit inside the hidden tab panel
     * while the script executes on page load — Chart.js instances are created even
     * before the Charts tab is clicked. On tab activation a {@code .resize()} call
     * corrects the canvas dimensions.</p>
     *
     * <p>When no {@code <script>} tag is present (unavailable-placeholder case), the
     * entire input is returned as the panel HTML with an empty script string.</p>
     *
     * @param chartsBlock full charts section HTML+JS from {@link #buildChartsSection};
     *                    may be null or empty
     * @return two-element array {@code [panelHtml, scriptHtml]};
     * {@code scriptHtml} is empty when no {@code <script>} tag is found
     */
    static String[] splitChartsBlock(String chartsBlock) {
        if (chartsBlock == null || chartsBlock.isBlank()) return new String[]{"", ""};
        int scriptIdx = chartsBlock.indexOf("<script>");
        if (scriptIdx < 0) return new String[]{chartsBlock, ""};
        return new String[]{
                chartsBlock.substring(0, scriptIdx),
                chartsBlock.substring(scriptIdx)
        };
    }

    // ─────────────────────────────────────────────────────────────
    // Charts section
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds the Performance Charts section from time buckets.
     *
     * <p>Always renders a charts section header — never silently omits it.
     * When {@code timeBuckets} is null, empty, or contains fewer than two entries,
     * a visible informational placeholder is rendered instead of blank canvas
     * elements. This ensures the HTML report is structurally complete regardless
     * of CLI flags (e.g. {@code --start-offset}) or test duration.</p>
     *
     * @param timeBuckets time buckets from the JTL parser (may be null or empty)
     * @return HTML+JS string for the charts section; never an empty string
     */
    static String buildChartsSection(List<JTLParser.TimeBucket> timeBuckets) {
        if (timeBuckets == null || timeBuckets.isEmpty()) {
            return buildChartsUnavailableSection(
                    "No time-series data is available for this report. "
                            + "This typically occurs when the test duration is shorter than the "
                            + "chart bucket interval, or when --start-offset / --end-offset filters "
                            + "exclude all samples. Try reducing --start-offset or running a longer test.");
        }
        if (timeBuckets.size() < 2) {
            return buildChartsUnavailableSection(
                    "Insufficient data for time-series charts — only one time bucket was captured. "
                            + "Try reducing --start-offset, increasing --chart-interval, or ensuring "
                            + "the test runs long enough to produce multiple data points.");
        }

        List<String> jLabels = new ArrayList<>();
        List<String> jAvg = new ArrayList<>();
        List<String> jErr = new ArrayList<>();
        List<String> jTps = new ArrayList<>();
        List<String> jKb = new ArrayList<>();

        for (JTLParser.TimeBucket b : timeBuckets) {
            String label = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(b.epochMs), ZoneId.systemDefault()).format(CHART_TIME_FMT);
            jLabels.add("\"" + label + "\"");
            jAvg.add(String.format(Locale.US, "%.2f", b.avgResponseMs));
            jErr.add(String.format(Locale.US, "%.2f", b.errorPct));
            jTps.add(String.format(Locale.US, "%.2f", b.tps));
            jKb.add(String.format(Locale.US, "%.2f", b.kbps));
        }

        // Append a phantom end-point at (last bucket start + interval) so Chart.js
        // extends the x-axis to the true test end time. The null data values ensure
        // nothing is plotted at this position — it is a label anchor only.
        long intervalMs = timeBuckets.get(1).epochMs - timeBuckets.get(0).epochMs;
        long phantomMs = timeBuckets.get(timeBuckets.size() - 1).epochMs + intervalMs;
        String phantomLabel = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(phantomMs), ZoneId.systemDefault()).format(CHART_TIME_FMT);
        jLabels.add("\"" + phantomLabel + "\"");
        jAvg.add("null");
        jErr.add("null");
        jTps.add("null");
        jKb.add("null");

        String labels = "[" + String.join(",", jLabels) + "]";
        String avgArr = "[" + String.join(",", jAvg) + "]";
        String errArr = "[" + String.join(",", jErr) + "]";
        String tpsArr = "[" + String.join(",", jTps) + "]";
        String kbArr = "[" + String.join(",", jKb) + "]";

        long intervalSeconds = (timeBuckets.get(1).epochMs - timeBuckets.get(0).epochMs) / 1_000L;

        return new StringBuilder(2048)
                .append("<div class=\"charts-section\">\n")
                .append("  <h2>Performance Charts Over Time</h2>\n")
                .append("  <p class=\"charts-note\">Each point represents a ")
                .append(intervalSeconds).append("-second interval.</p>\n")
                .append(chartBox("chartAvgRt", "Average Response Time Over Time (ms)"))
                .append(chartBox("chartErrPct", "Error Rate Over Time (%)"))
                .append(chartBox("chartTps", "Throughput Over Time (req/s)"))
                .append(chartBox("chartKb", "Received Bandwidth Over Time (KB/s)"))
                .append("</div>\n")
                .append("<script>\n(function() {\n")
                .append("  var labels = ").append(labels).append(";\n")
                .append(buildTimeSeriesChartFn())
                .append("  timeChart('chartAvgRt',  ").append(avgArr).append(", 'Avg Response Time', 'ms',    'rgba(49,130,206,1)');\n")
                .append("  timeChart('chartErrPct', ").append(errArr).append(", 'Error Rate',        '%',     'rgba(229,62,62,1)');\n")
                .append("  timeChart('chartTps',    ").append(tpsArr).append(", 'Throughput',        'req/s', 'rgba(72,187,120,1)');\n")
                .append("  timeChart('chartKb',     ").append(kbArr).append(",  'Bandwidth',         'KB/s',  'rgba(159,122,234,1)');\n")
                .append("})();\n</script>\n")
                .toString();
    }

    /**
     * Renders a charts section placeholder when time-series data is unavailable.
     *
     * @param reason human-readable explanation shown in the report
     * @return HTML string for the placeholder charts section
     */
    private static String buildChartsUnavailableSection(String reason) {
        return "<div class=\"charts-section\">\n"
                + "  <h2>Performance Charts Over Time</h2>\n"
                + "  <p class=\"charts-note charts-unavailable\">"
                + HtmlReportRenderer.escapeHtml(reason)
                + "</p>\n"
                + "</div>\n";
    }

    // ─────────────────────────────────────────────────────────────
    // Markdown conversion
    // ─────────────────────────────────────────────────────────────

    /**
     * Converts Markdown to HTML, sanitising then pre-processing GFM pipe tables
     * before passing to the CommonMark parser.
     *
     * <p>Processing order:</p>
     * <ol>
     *   <li>{@link #stripRawHtml(String)} — removes any raw HTML blocks from the
     *       AI-generated Markdown before any other transformation runs. At this point
     *       no trusted {@code <table>} blocks have been injected yet, so the strip is
     *       safe: it cannot accidentally remove our own pipe-table output.</li>
     *   <li>{@link #convertPipeTablesToHtml(String)} — converts GFM pipe tables to
     *       trusted {@code <table>} blocks.</li>
     *   <li>CommonMark render — {@code escapeHtml(false)} is required so the trusted
     *       {@code <table>} blocks injected in step 2 pass through verbatim.</li>
     * </ol>
     *
     * @param markdown raw AI-generated Markdown string
     * @return sanitised HTML string
     */
    static String markdownToHtml(String markdown) {
        String sanitised = stripRawHtml(markdown);
        String preprocessed = convertPipeTablesToHtml(sanitised);
        Parser parser = Parser.builder().build();
        HtmlRenderer renderer = HtmlRenderer.builder().escapeHtml(false).build();
        return renderer.render(parser.parse(preprocessed));
    }

    /**
     * Converts GFM pipe tables embedded in {@code text} to HTML {@code <table>} blocks.
     * Cell content is HTML-escaped to prevent injection of AI-generated markup.
     *
     * @param text Markdown text potentially containing pipe tables
     * @return text with pipe tables replaced by HTML table markup
     */
    static String convertPipeTablesToHtml(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.length) {
            if (i + 1 < lines.length && isPipeRow(lines[i]) && isSeparatorRow(lines[i + 1])) {
                List<String[]> tableLines = new ArrayList<>();
                tableLines.add(splitPipeRow(lines[i]));
                i += 2;
                while (i < lines.length && isPipeRow(lines[i])) {
                    tableLines.add(splitPipeRow(lines[i++]));
                }
                out.append(renderPipeTable(tableLines));
            } else {
                out.append(lines[i++]).append("\n");
            }
        }
        return out.toString();
    }

    /**
     * Removes raw HTML blocks from AI-generated Markdown before any further processing.
     *
     * <p>A line is treated as a raw HTML block when its trimmed content starts with
     * {@code <} followed immediately by a letter (opening tag, e.g. {@code <script>})
     * or {@code /} (closing tag, e.g. {@code </div>}). Such lines are discarded entirely.
     * All other lines — including blank lines and Markdown pipe-table rows — are preserved.</p>
     *
     * <p>This method is intentionally called <em>before</em>
     * {@link #convertPipeTablesToHtml(String)} so that no trusted {@code <table>} blocks
     * exist yet; the strip therefore cannot remove our own injected markup.</p>
     *
     * @param markdown raw AI-generated Markdown (may be null)
     * @return Markdown with raw HTML lines removed; returns input unchanged if null or blank
     */
    static String stripRawHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        String[] lines = markdown.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() >= 2
                    && trimmed.charAt(0) == '<'
                    && (Character.isLetter(trimmed.charAt(1)) || trimmed.charAt(1) == '/')) {
                continue; // raw HTML block — discard
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String renderPipeTable(List<String[]> tableLines) {
        StringBuilder sb = new StringBuilder("<table>\n<thead>\n<tr>");
        for (String cell : tableLines.get(0)) {
            sb.append("<th>").append(HtmlReportRenderer.escapeHtml(cell.trim())).append("</th>");
        }
        sb.append("</tr>\n</thead>\n<tbody>\n");
        for (int r = 1; r < tableLines.size(); r++) {
            sb.append("<tr>");
            for (String cell : tableLines.get(r)) {
                sb.append("<td>").append(HtmlReportRenderer.escapeHtml(cell.trim())).append(HtmlReportRenderer.TD_CLOSE);
            }
            sb.append("</tr>\n");
        }
        return sb.append("</tbody>\n</table>\n").toString();
    }

    /**
     * Returns {@code true} when {@code line} looks like a GFM table data or header row.
     *
     * <p>Detection is format-agnostic: the only requirement is that the trimmed
     * line contains at least one {@code |} character. This covers all four pipe
     * placement variants emitted by different AI providers:</p>
     * <ul>
     *   <li>{@code | Col1 | Col2 |}  — leading + trailing pipe</li>
     *   <li>{@code | Col1 | Col2}    — leading pipe, no trailing pipe</li>
     *   <li>{@code Col1 | Col2 |}    — no leading pipe, trailing pipe</li>
     *   <li>{@code Col1 | Col2}      — no edge pipes (interior pipe only)</li>
     * </ul>
     */
    private static boolean isPipeRow(String line) {
        return line.contains("|");
    }

    /**
     * Returns {@code true} when {@code line} is a GFM separator row
     * (the {@code |---|---|} line between header and body).
     *
     * <p>Format-agnostic: strips all {@code |}, {@code -}, {@code :}, and
     * whitespace characters and requires the result to be empty, plus at least
     * one {@code -} to rule out blank lines or lines containing only pipes.</p>
     */
    private static boolean isSeparatorRow(String line) {
        String t = line.trim();
        if (!t.contains("|")) return false;
        if (!t.contains("-")) return false;
        return t.replaceAll("[|\\-:\\s]", "").isEmpty();
    }

    /**
     * Splits a GFM pipe-table row into trimmed cell tokens.
     *
     * <p>Strips leading and trailing {@code |} when present (conditionally),
     * then splits on {@code |}. Works correctly for all four pipe-placement
     * variants since the strip is conditional in both cases.</p>
     */
    private static String[] splitPipeRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        return t.split("\\|", -1);
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers — CSS, JS, meta row
    // ─────────────────────────────────────────────────────────────

    private static String buildRunDateTime(String startTime, String endTime) {
        boolean hasStart = startTime != null && !startTime.isBlank();
        boolean hasEnd = endTime != null && !endTime.isBlank();
        if (hasStart && hasEnd) return startTime.trim() + " - " + endTime.trim();
        if (hasStart) return startTime.trim();
        return "";
    }

    private static void appendMetaRow(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) return;
        sb.append("  <tr>")
                .append("<td class=\"meta-label\">").append(label).append("</td>")
                .append("<td class=\"meta-value\">").append(HtmlReportRenderer.escapeHtml(value)).append("</td>")
                .append("</tr>\n");
    }

    private static String chartBox(String canvasId, String title) {
        return new StringBuilder(256)
                .append("  <div class=\"chart-box\">\n")
                .append("    <h3>").append(title).append("</h3>\n")
                .append("    <div class=\"chart-canvas-wrap\"><canvas id=\"").append(canvasId).append("\"></canvas></div>\n")
                .append("  </div>\n")
                .toString();
    }

    private static String buildTimeSeriesChartFn() {
        return new StringBuilder(1024)
                .append("  function timeChart(id, data, label, unit, color) {\n")
                .append("    new Chart(document.getElementById(id), {\n")
                .append("      type: 'line',\n")
                .append("      data: { labels: labels, datasets: [{\n")
                .append("        label: label, data: data, borderColor: color,\n")
                .append("        backgroundColor: color.replace('1)', '0.10)'),\n")
                .append("        borderWidth: 2, pointRadius: 3, pointHoverRadius: 6,\n")
                .append("        fill: true, tension: 0.25, spanGaps: false\n")
                .append("      }]},\n")
                .append("      options: {\n")
                .append("        responsive: true, maintainAspectRatio: false,\n")
                .append("        plugins: {\n")
                .append("          legend: { display: false },\n")
                .append("          tooltip: { callbacks: { label: function(ctx) {\n")
                .append("            if (ctx.parsed.y === null) return null;\n")
                .append("            return ' ' + ctx.parsed.y.toFixed(2) + ' ' + unit;\n")
                .append("          }}}\n")
                .append("        },\n")
                .append("        scales: {\n")
                .append("          x: { title: { display: true, text: 'Test Time (HH:mm:ss)', font: { size: 11 } },\n")
                .append("               ticks: { font: { size: 10 }, maxRotation: 45, autoSkip: true, maxTicksLimit: 12,\n")
                .append("                 callback: function(val, idx, all) {\n")
                .append("                   if (idx === 0 || idx === all.length - 1) return this.getLabelForValue(val);\n")
                .append("                   if (idx % Math.round(all.length / 10) === 0) return this.getLabelForValue(val);\n")
                .append("                   return undefined;\n")
                .append("                 }\n")
                .append("               },\n")
                .append("               grid: { color: 'rgba(0,0,0,0.04)' } },\n")
                .append("          y: { beginAtZero: true,\n")
                .append("               title: { display: true, text: unit, font: { size: 11 } },\n")
                .append("               ticks: { font: { size: 11 } },\n")
                .append("               grid: { color: 'rgba(0,0,0,0.04)' } }\n")
                .append("        }\n")
                .append("      }\n")
                .append("    });\n")
                .append("  }\n")
                .toString();
    }

    /**
     * Builds the inline JavaScript for tab switching and Excel export.
     *
     * <h4>Tab switching</h4>
     * <p>Each tab button stores its panel index in {@code data-tab}. On click, all
     * active classes are cleared and reapplied to the clicked button and its panel.
     * When the Charts tab is activated ({@code data-charts="true"}), all Chart.js
     * instances are resized to fix the 0×0 canvas dimensions from hidden initialisation.
     * {@code Chart.instances} is a plain object in Chart.js 4.x — iterated via
     * {@code Object.values()}.</p>
     *
     * <h4>Excel export (SheetJS)</h4>
     * <p>One worksheet per tab panel, named by the panel's {@code data-title}.
     * Charts panels receive a placeholder message (canvases are not serialisable).
     * Table panels use {@code XLSX.utils.table_to_sheet}. Prose-only panels produce
     * a single wide text column.</p>
     *
     * @return self-executing {@code <script>} block string
     */
    private static String buildTabJs() {
        return new StringBuilder(2048)
                .append("<script>\n(function() {\n")
                // ── Tab switching ──────────────────────────────────────────────
                .append("  document.querySelectorAll('.tab-btn').forEach(function(btn) {\n")
                .append("    btn.addEventListener('click', function() {\n")
                .append("      document.querySelectorAll('.tab-btn').forEach(function(b) {\n")
                .append("        b.classList.remove('active');\n")
                .append("      });\n")
                .append("      document.querySelectorAll('.tab-panel').forEach(function(p) {\n")
                .append("        p.classList.remove('active');\n")
                .append("      });\n")
                .append("      btn.classList.add('active');\n")
                .append("      document.getElementById('tab-' + btn.dataset.tab).classList.add('active');\n")
                .append("      if (btn.dataset.charts === 'true' && typeof Chart !== 'undefined') {\n")
                .append("        Object.values(Chart.instances).forEach(function(inst) {\n")
                .append("          inst.resize();\n")
                .append("        });\n")
                .append("      }\n")
                .append("    });\n")
                .append("  });\n\n")
                // ── Excel export ───────────────────────────────────────────────
                .append("  function exportExcel() {\n")
                .append("    if (typeof XLSX === 'undefined') {\n")
                .append("      alert('Excel export requires an internet connection to load SheetJS.');\n")
                .append("      return;\n")
                .append("    }\n")
                .append("    var wb = XLSX.utils.book_new();\n")
                // ── Sheet 1: Test Info — always first ──────────────────────────
                .append("    var infoRows = [['Field', 'Value']];\n")
                .append("    if (window.jaarMeta) {\n")
                .append("      var m = window.jaarMeta;\n")
                .append("      if (m.scenarioName) infoRows.push(['Scenario Name', m.scenarioName]);\n")
                .append("      if (m.scenarioDesc) infoRows.push(['Scenario Description', m.scenarioDesc]);\n")
                .append("      if (m.users)        infoRows.push(['Virtual Users', m.users]);\n")
                .append("      if (m.runDateTime)  infoRows.push(['Run Date/Time', m.runDateTime]);\n")
                .append("      if (m.duration)     infoRows.push(['Duration', m.duration]);\n")
                .append("    }\n")
                .append("    var wsInfo = XLSX.utils.aoa_to_sheet(infoRows);\n")
                .append("    wsInfo['!cols'] = [{wch: 25}, {wch: 80}];\n")
                .append("    XLSX.utils.book_append_sheet(wb, wsInfo, 'Test Info');\n")
                // ── Sheets 2–N: one per tab panel ──────────────────────────────
                .append("    document.querySelectorAll('.tab-panel').forEach(function(panel) {\n")
                .append("      var title = panel.dataset.title || 'Sheet';\n")
                .append("      var sheetName = title.substring(0, 31);\n")
                .append("      var ws;\n")
                // Charts panel: write a referral message — PNG charts require SheetJS Pro
                .append("      if (panel.querySelector('canvas')) {\n")
                .append("        ws = XLSX.utils.aoa_to_sheet([\n")
                .append("          ['Performance charts are not available in Excel export.'],\n")
                .append("          ['Please refer to the HTML report for interactive charts.']\n")
                .append("        ]);\n")
                .append("        ws['!cols'] = [{wch: 65}];\n")
                .append("      } else if (panel.querySelector('table')) {\n")
                .append("        ws = XLSX.utils.table_to_sheet(panel.querySelector('table'));\n")
                .append("      } else {\n")
                .append("        var text = panel.innerText || '';\n")
                .append("        var rows = text.split('\\n')\n")
                .append("          .filter(function(l) { return l.trim().length > 0; })\n")
                .append("          .map(function(l) { return [l.trim()]; });\n")
                .append("        ws = XLSX.utils.aoa_to_sheet(rows.length > 0 ? rows : [['(empty)']]);\n")
                .append("        ws['!cols'] = [{wch: 120}];\n")
                .append("      }\n")
                .append("      XLSX.utils.book_append_sheet(wb, ws, sheetName);\n")
                .append("    });\n")
                .append("    var provider = (window.jaarMeta && window.jaarMeta.providerName)\n")
                .append("      ? window.jaarMeta.providerName : 'AI';\n")
                .append("    var ts = new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14);\n")
                .append("    XLSX.writeFile(wb, 'JAAR_' + provider + '_Report_' + ts + '.xlsx');\n")
                .append("  }\n")
                .append("  window.exportExcel = exportExcel;\n")
                .append("})();\n</script>\n")
                .toString();
    }


    /**
     * Builds an inline {@code <script>} block that exposes scenario metadata as
     * {@code window.jaarMeta} so the Excel export function can build the
     * "Test Info" sheet and derive the dynamic Excel filename without parsing the
     * visible HTML.
     *
     * <p>{@code providerName} is the sanitized, tier-stripped provider segment used
     * in filenames — e.g. {@code "Groq (Free)"} → {@code "Groq"} — matching the
     * same stripping logic applied to the HTML report filename in
     * {@link com.personal.jmeter.ai.report.AiReportCoordinator}.</p>
     *
     * <p>Values are single-quoted JS strings with {@code '}, {@code \},
     * newline, and carriage-return characters escaped.</p>
     *
     * @param config      scenario metadata from the render config
     * @param runDateTime pre-formatted run date/time string (may be empty)
     * @return inline {@code <script>} block string
     */
    private static String buildMetaScript(HtmlReportRenderer.RenderConfig config,
                                          String runDateTime) {
        String providerName = sanitizeProviderName(config.providerDisplayName);
        return "<script>\n"
                + "window.jaarMeta = {\n"
                + "  scenarioName:  " + jsString(config.scenarioName) + ",\n"
                + "  scenarioDesc:  " + jsString(config.scenarioDesc) + ",\n"
                + "  users:         " + jsString(config.users) + ",\n"
                + "  runDateTime:   " + jsString(runDateTime) + ",\n"
                + "  duration:      " + jsString(config.duration) + ",\n"
                + "  providerName:  " + jsString(providerName) + "\n"
                + "};\n"
                + "</script>\n";
    }

    /**
     * Strips the parenthetical tier suffix from a provider display name and
     * replaces any filesystem-unsafe characters with underscores, producing a
     * segment safe for use in filenames.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code "Groq (Free)"}   → {@code "Groq"}</li>
     *   <li>{@code "OpenAI (Paid)"} → {@code "OpenAI"}</li>
     *   <li>{@code "My Provider"}   → {@code "My_Provider"}</li>
     *   <li>{@code null / blank}    → {@code "AI"}</li>
     * </ul>
     *
     * @param providerDisplayName raw display name from {@link HtmlReportRenderer.RenderConfig}
     * @return sanitized filename segment; never null or empty
     */
    static String sanitizeProviderName(String providerDisplayName) {
        if (providerDisplayName == null || providerDisplayName.isBlank()) return "AI";
        // Strip parenthetical tier suffix: "Groq (Free)" → "Groq"
        String base = providerDisplayName.replaceAll("\\s*\\(.*\\)\\s*$", "").trim();
        // Replace filesystem-unsafe characters and whitespace runs with underscore
        String sanitized = base.replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return sanitized.isEmpty() ? "AI" : sanitized;
    }

    /**
     * Wraps {@code value} in single quotes, escaping characters that would
     * break a JS string literal: backslash, single quote, CR, and LF.
     *
     * @param value raw string (may be null or blank)
     * @return JS single-quoted string literal, e.g. {@code 'Load Test'}
     */
    private static String jsString(String value) {
        if (value == null || value.isBlank()) return "''";
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "")
                .replace("\n", "\\n") + "'";
    }

    @SuppressWarnings("java:S5665") // CSS string — not a sensitive data concatenation
    private static String buildCss() {
        return new StringBuilder(4096)
                .append("  <style>\n")
                .append("    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n")
                .append("    body { font-family: 'Segoe UI', system-ui, sans-serif; font-size: 14px;\n")
                .append("           line-height: 1.7; color: #1a202c; background: #f7f8fc; }\n")
                .append("    .report-header { background: linear-gradient(135deg, #1a365d 0%, #2b6cb0 100%);\n")
                .append("                     color: white; padding: 32px 48px 28px; }\n")
                .append("    .report-header h1 { font-size: 24px; font-weight: 700; margin-bottom: 16px; }\n")
                .append("    .report-header .meta-table { border-collapse: collapse; background: transparent;\n")
                .append("                                  box-shadow: none; border-radius: 0; margin: 0; width: auto; }\n")
                .append("    .report-header .meta-table td { padding: 3px 0; font-size: 13px;\n")
                .append("                                     border-bottom: none; background: transparent !important; }\n")
                .append("    .report-header .meta-table tr:nth-child(even) td { background: transparent !important; }\n")
                .append("    .report-header .meta-table .meta-label { color: rgba(255,255,255,0.70); font-weight: 600;\n")
                .append("                                              padding-right: 16px; white-space: nowrap; }\n")
                .append("    .report-header .meta-table .meta-value { color: white; }\n")
                // ── Tab bar ──────────────────────────────────────────────────
                .append("    .tab-bar { display: flex; flex-wrap: wrap; gap: 4px;\n")
                .append("               padding: 8px 16px; background: #edf2f7;\n")
                .append("               border-bottom: 2px solid #cbd5e0;\n")
                .append("               position: sticky; top: 0; z-index: 10; }\n")
                .append("    .tab-btn { padding: 7px 14px; border: 1px solid #cbd5e0; border-bottom: none;\n")
                .append("               background: #fff; cursor: pointer; border-radius: 6px 6px 0 0;\n")
                .append("               font-size: 13px; font-family: inherit; white-space: nowrap; color: #4a5568; }\n")
                .append("    .tab-btn:hover { background: #e2e8f0; }\n")
                .append("    .tab-btn.active { background: #2b6cb0; color: #fff;\n")
                .append("                      font-weight: 600; border-color: #2b6cb0; }\n")
                // ── Export bar ───────────────────────────────────────────────
                .append("    .export-bar { display: flex; gap: 8px; padding: 8px 16px;\n")
                .append("                  background: #f7f8fc; border-bottom: 1px solid #e2e8f0; }\n")
                .append("    .export-bar button { padding: 6px 14px; border: 1px solid #cbd5e0;\n")
                .append("                         background: #fff; cursor: pointer; border-radius: 4px;\n")
                .append("                         font-size: 13px; font-family: inherit; color: #2d3748; }\n")
                .append("    .export-bar button:hover { background: #e2e8f0; }\n")
                // ── Tab panels ───────────────────────────────────────────────
                .append("    .tab-panel { display: none; padding: 16px 0; }\n")
                .append("    .tab-panel.active { display: block; }\n")
                // ── Content area ─────────────────────────────────────────────
                .append("    .content { max-width: 1000px; margin: 32px auto; padding: 0 24px; }\n")
                .append("    h1 { display: none; }\n")
                .append("    h2 { font-size: 17px; font-weight: 700; color: #1a365d;\n")
                .append("         border-left: 4px solid #3182ce; padding-left: 12px;\n")
                .append("         margin: 32px 0 12px; }\n")
                .append("    h3 { font-size: 14px; font-weight: 600; color: #2d3748; margin: 18px 0 8px; }\n")
                .append("    p  { margin-bottom: 12px; }\n")
                .append("    table { border-collapse: collapse; width: 100%; margin: 14px 0 20px;\n")
                .append("            background: white; border-radius: 6px; overflow: hidden;\n")
                .append("            box-shadow: 0 1px 4px rgba(0,0,0,0.08); }\n")
                .append("    th { background: #2d3748; color: white; padding: 9px 14px;\n")
                .append("         text-align: left; font-size: 12px; font-weight: 600;\n")
                .append("         text-transform: uppercase; letter-spacing: 0.5px; }\n")
                .append("    td { padding: 8px 14px; border-bottom: 1px solid #edf2f7; font-size: 13px; }\n")
                .append("    td.num { text-align: right; font-variant-numeric: tabular-nums; }\n")
                .append("    td.sla-pass { text-align: center; font-weight: 600; color: #276749; }\n")
                .append("    td.sla-fail { text-align: center; font-weight: 600; color: #c53030; }\n")
                .append("    td.sla-na   { text-align: center; color: #a0aec0; }\n")
                .append("    tr:last-child td { border-bottom: none; }\n")
                .append("    tr:nth-child(even) td { background: #f7fafc; }\n")
                .append("    code { background: #edf2f7; padding: 2px 6px; border-radius: 4px;\n")
                .append("           font-family: Consolas, monospace; font-size: 12px; color: #c53030; }\n")
                .append("    pre  { background: #2d3748; color: #e2e8f0; padding: 14px 18px;\n")
                .append("           border-radius: 6px; overflow-x: auto; margin: 12px 0 18px; }\n")
                .append("    pre code { background: none; color: inherit; padding: 0; }\n")
                .append("    blockquote { border-left: 4px solid #63b3ed; margin: 14px 0;\n")
                .append("                 padding: 8px 14px; background: #ebf8ff;\n")
                .append("                 border-radius: 0 6px 6px 0; }\n")
                .append("    ul, ol { margin: 8px 0 14px 24px; }\n")
                .append("    li { margin-bottom: 4px; }\n")
                .append("    strong { font-weight: 600; }\n")
                .append("    .metrics-section { margin: 40px 0 0; }\n")
                .append("    .charts-section  { margin: 40px 0 0; }\n")
                .append("    .charts-note { font-size: 12px; color: #718096; margin-bottom: 20px; }\n")
                .append("    .charts-unavailable { background: #fffbeb; border: 1px solid #f6e05e;\n")
                .append("                          border-radius: 6px; padding: 12px 16px;\n")
                .append("                          color: #744210; font-size: 13px; }\n")
                .append("    .chart-box { background: white; border-radius: 8px; padding: 20px 24px 16px;\n")
                .append("                 box-shadow: 0 1px 4px rgba(0,0,0,0.08); margin-bottom: 24px; }\n")
                .append("    .chart-box h3 { font-size: 13px; font-weight: 600; color: #2d3748;\n")
                .append("                    margin: 0 0 14px; border: none; padding: 0; }\n")
                .append("    .chart-canvas-wrap { position: relative; height: 280px; }\n")
                .append("    .footer { text-align: center; font-size: 11px; color: #a0aec0;\n")
                .append("              margin: 48px 0 24px; padding-top: 14px;\n")
                .append("              border-top: 1px solid #e2e8f0; }\n")
                // ── Print / PDF ───────────────────────────────────────────────
                .append("    @media print {\n")
                .append("      .tab-bar    { display: none; }\n")
                .append("      .export-bar { display: none; }\n")
                .append("      .tab-panel  { display: block !important;\n")
                .append("                    page-break-inside: avoid; margin-bottom: 24px; }\n")
                .append("      .tab-panel h2 { page-break-after: avoid; }\n")
                .append("      canvas { max-width: 100%; height: auto !important; }\n")
                .append("      .report-header { background: #1a365d !important;\n")
                .append("                       -webkit-print-color-adjust: exact; }\n")
                .append("      body { background: white; }\n")
                .append("    }\n")
                .append("  </style>\n")
                .toString();
    }
}