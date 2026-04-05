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
 *   <li>Up to 9 clickable tabs — Executive Summary, Transaction Metrics,
 *       5 AI analysis sections, Performance Charts, Verdict
 *       (metrics tab omitted when empty)</li>
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
    private static final DateTimeFormatter FOOTER_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
     * Assembles the full standalone HTML page with sidebar navigation
     * and Excel / PDF export buttons.
     *
     * <p>Page structure (top to bottom):</p>
     * <ol>
     *   <li>Header ({@code .rpt-header}) — title, AI-notice subtitle, metadata grid,
     *       and Export Excel / Export PDF buttons (always visible)</li>
     *   <li>Body row ({@code .body-row}) — flex row containing sidebar + main column</li>
     *   <li>Sidebar ({@code .sidebar}) — vertical sticky nav; one button per section</li>
     *   <li>Content area ({@code .content-area}) — {@code .panel} divs; only active is displayed</li>
     * </ol>
     *
     * <p>Tab ordering: Executive Summary → Transaction Metrics → Bottleneck Analysis →
     * Error Analysis → Advanced Web Diagnostics → Root Cause Hypotheses →
     * Recommendations → Performance Charts → Verdict. Transaction Metrics is
     * inserted at index 1 (after Executive Summary); Performance Charts is
     * inserted before Verdict (the last AI section).</p>
     *
     * <p>The Chart.js initialisation {@code <script>} block is placed
     * <em>outside</em> its panel so Chart.js instances are created on page
     * load even when the Charts panel is hidden. On panel activation a resize
     * call corrects the 0×0 canvas dimensions.</p>
     *
     * @param htmlBody       the AI-generated analysis converted from Markdown
     * @param metricsTable   the Transaction Metrics HTML section (may be empty)
     * @param chartsBlock    the Performance Charts HTML section (may be empty)
     * @param config         scenario metadata
     * @param errorBreakdown Java-computed error breakdown table HTML (may be empty)
     * @param latencyPanel   Java-computed latency KPI panel HTML (may be empty)
     * @param verdict        extracted AI verdict ("PASS", "FAIL", "UNDECISIVE")
     * @return complete HTML document as a string
     */
    static String buildPage(String htmlBody, String metricsTable,
                            String chartsBlock, HtmlReportRenderer.RenderConfig config,
                            String errorBreakdown, String latencyPanel,
                            String verdict) {

        String runDateTime = buildRunDateTime(config.startTime, config.endTime);
        String providerDisplay = HtmlReportRenderer.escapeHtml(
                config.providerDisplayName.isBlank() ? "AI" : config.providerDisplayName);

        StringBuilder metaGrid = new StringBuilder("<div class=\"meta-grid\">\n");
        appendMetaRow(metaGrid, "Scenario Name", config.scenarioName, false);
        appendMetaRow(metaGrid, "Scenario Description", config.scenarioDesc, false);
        appendMetaRow(metaGrid, "Virtual Users", config.users, false);
        if (!runDateTime.isEmpty()) appendMetaRow(metaGrid, "Run Date/Time", runDateTime, false);
        appendMetaRow(metaGrid, "Duration", config.duration, true);
        metaGrid.append("</div>\n");

        // ── Section list ─────────────────────────────────────────────────────
        // AI sections split from htmlBody at <h2> boundaries (typically 7:
        // Executive Summary, Bottleneck, Error, Diagnostics, RCA, Recommendations, Verdict)
        String styledBody = styleVerdictTokens(htmlBody != null ? htmlBody : "");
        List<String[]> sections = splitAtH2(styledBody);

        // Transaction Metrics: insert at index 1 (after Executive Summary)
        // so the data table is immediately accessible after the summary overview.
        // When splitAtH2 returns 0 or 1 sections (truncated AI), appends at end.
        String safeMetrics = metricsTable != null ? metricsTable : "";
        if (!safeMetrics.isBlank()) {
            int metricsInsertIdx = Math.min(1, sections.size());
            sections.add(metricsInsertIdx, new String[]{"Transaction Metrics", safeMetrics});
        }

        // Warn when AI response appears truncated (fewer than 5 sections expected)
        if (sections.size() > 0 && sections.size() < 5) {
            String warn = "<div class=\"ai-notice\">"
                    + "&#9888; AI analysis appears incomplete \u2014 some sections may be missing. "
                    + "Consider increasing <code>max.tokens</code> in ai-reporter.properties.</div>\n";
            sections.get(0)[1] = warn + sections.get(0)[1];
        }

        // Prepend Java-computed data blocks to their matching AI sections.
        // Error breakdown → "Error Analysis"; Latency panel → "Advanced Web Diagnostics".
        String safeErrorBreakdown = errorBreakdown != null ? errorBreakdown : "";
        String safeLatencyPanel = latencyPanel != null ? latencyPanel : "";
        if (!safeErrorBreakdown.isBlank() || !safeLatencyPanel.isBlank()) {
            for (String[] section : sections) {
                String title = section[0].toLowerCase(Locale.ROOT);
                if (!safeErrorBreakdown.isBlank() && title.contains("error")) {
                    section[1] = injectAfterH2(section[1], safeErrorBreakdown);
                }
                if (!safeLatencyPanel.isBlank() && title.contains("diagnostics")) {
                    section[1] = injectAfterH2(section[1], safeLatencyPanel);
                }
            }
        }

        // Performance Charts: insert before the last section (Verdict).
        // Split the Chart.js <script> block out of the panel so instances are
        // created on page load regardless of which panel is initially visible.
        String[] chartsParts = splitChartsBlock(chartsBlock != null ? chartsBlock : "");
        String chartsContent = chartsParts[0]; // <div class="charts-section">…canvases…</div>
        String chartsScript = chartsParts[1]; // <script>(function(){…})();</script>
        int chartsInsertIdx = Math.max(0, sections.size() - 1);
        sections.add(chartsInsertIdx, new String[]{"Performance Charts", chartsContent});

        // ── Sidebar navigation ──────────────────────────────────────────────────
        StringBuilder sidebar = new StringBuilder("<nav class=\"sidebar\">\n");
        for (int i = 0; i < sections.size(); i++) {
            String title = sections.get(i)[0];
            String titleLower = title.toLowerCase(Locale.ROOT);
            String status = "";
            if (titleLower.contains("verdict") && !"UNDECISIVE".equals(verdict)) {
                status = "PASS".equals(verdict) ? "pass" : "fail";
            } else if (titleLower.contains("error")
                    && config.errorTypeSummary != null && !config.errorTypeSummary.isEmpty()) {
                status = "warn";
            }
            sidebar.append("  <button class=\"nav-item")
                    .append(i == 0 ? " active" : "")
                    .append("\" data-panel=\"panel-").append(i).append("\"");
            if (!status.isEmpty()) {
                sidebar.append(" data-status=\"").append(status).append("\"");
            }
            sidebar.append(">")
                    .append(HtmlReportRenderer.escapeHtml(title))
                    .append("</button>\n");
        }
        sidebar.append("</nav>\n");

        // ── Panels ──────────────────────────────────────────────────────────────
        // class="panel", id="panel-{i}", data-title for Excel export sheet naming.
        // No ai-notice div — AI notice is in the header .sub line only.
        StringBuilder panels = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            String title = sections.get(i)[0];
            String content = sections.get(i)[1];
            panels.append("<div class=\"panel")
                    .append(i == 0 ? " active" : "")
                    .append("\" id=\"panel-").append(i).append("\"")
                    .append(" data-title=\"").append(HtmlReportRenderer.escapeHtml(title)).append("\">\n")
                    .append(content)
                    .append("\n</div>\n");
        }

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
                // ── Outer wrapper ──────────────────────────────────────────────────
                .append("<div class=\"rpt\">\n")
                .append("  <div class=\"rpt-header\">\n")
                .append("    <div>\n")
                .append("      <h1>JMeter AI Performance Report</h1>\n")
                .append("      <div class=\"sub\">Generated by JAAR Plugin using ").append(providerDisplay)
                .append(" &nbsp;|&nbsp; &#9888; AI-Generated Analysis &mdash; Validate Before Use.</div>\n")
                .append(buildVerdictBadge(verdict))
                .append("      ").append(metaGrid)
                .append("    </div>\n")
                .append("    <div class=\"header-actions\">\n")
                .append("      <button class=\"exp-btn\" onclick=\"exportExcel()\">&#x1F4E5;&nbsp; Export Excel</button>\n")
                .append("      <button class=\"exp-btn\" onclick=\"window.print()\">&#x1F4C4;&nbsp; Export PDF</button>\n")
                .append("      <button class=\"exp-btn\" id=\"darkToggle\" onclick=\"toggleDark()\">&#x1F319;&nbsp; Dark Mode</button>\n")
                .append("    </div>\n")
                .append("  </div>\n")
                .append("  <div class=\"body-row\">\n")
                .append("    ").append(sidebar)
                .append("    <div class=\"main-col\">\n")
                .append("      <div class=\"content-area\">\n")
                .append(panels)
                .append("      </div>\n")
                .append("    </div>\n")
                .append("  </div>\n")
                .append("  <div class=\"footer-rpt\">Report generated on ")
                .append(LocalDateTime.now().format(FOOTER_TIME_FMT))
                .append(" using ").append(providerDisplay).append("</div>\n")
                .append("</div>\n")
                .append(chartsScript)   // Chart.js init OUTSIDE panels — avoids 0x0 canvas bug
                .append(buildTabJs())   // sidebar switching + Excel export
                .append(buildMetricsJs()) // metrics table: search, pagination, column sort
                .append(buildDarkModeJs())
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
                            + "exclude all samples.",
                    "Try reducing --start-offset or running a longer test.");
        }
        if (timeBuckets.size() < 2) {
            return buildChartsUnavailableSection(
                    "Only One Time Bucket Was Captured.",
                    "To Generate Charts: Set <code>Chart Interval (s)</code> &gt; 0 In The JAAR Plugin,"
                            + " Or Ensure The Test Runs Long Enough For Multiple Intervals.");
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
                .append("  <p class=\"charts-note\">Each point represents ")
                .append(intervalSeconds).append("-second interval.</p>\n")
                .append("  <div class=\"charts-grid\">\n")
                .append(chartBox("chartAvgRt", "Average Response Time Over Time (ms)"))
                .append(chartBox("chartErrPct", "Error Rate Over Time (%)"))
                .append(chartBox("chartTps", "Throughput Over Time (req/s)"))
                .append(chartBox("chartKb", "Received Bandwidth Over Time (KB/s)"))
                .append("  </div>\n")
                .append("</div>\n")
                .append("<script>\n(function() {\n")
                .append("  var labels = ").append(labels).append(";\n")
                .append(buildTimeSeriesChartFn())
                .append("  timeChart('chartAvgRt',  ").append(avgArr).append(", 'Avg Response Time', 'ms',    'rgba(49,130,206,1)');\n")
                .append("  timeChart('chartErrPct', ").append(errArr).append(", 'Error Rate',        '%',     'rgba(229,62,62,1)',   [6,3]);\n")
                .append("  timeChart('chartTps',    ").append(tpsArr).append(", 'Throughput',        'req/s', 'rgba(72,187,120,1)',  [2,2]);\n")
                .append("  timeChart('chartKb',     ").append(kbArr).append(",  'Bandwidth',         'KB/s',  'rgba(159,122,234,1)', [1,3]);\n")
                .append("})();\n</script>\n")
                .toString();
    }

    /**
     * Renders a charts section placeholder when time-series data is unavailable.
     *
     * <p>Emits a {@code .charts-warn} div containing a bold title, the plain-text
     * {@code reason}, and a {@code remedy} string that may include trusted HTML
     * (e.g. {@code <code>} tags). The reason is HTML-escaped; the remedy is
     * emitted verbatim and must be safe at the call site.</p>
     *
     * @param reason plain-text explanation of why data is unavailable (will be HTML-escaped)
     * @param remedy HTML remedy string shown after the reason (emitted verbatim)
     * @return HTML string for the placeholder charts section
     */
    private static String buildChartsUnavailableSection(String reason, String remedy) {
        return "<div class=\"charts-section\">\n"
                + "  <h2>Performance Charts Over Time</h2>\n"
                + "  <div class=\"charts-warn\">\n"
                + "    <strong>Insufficient Data For Time-Series Charts</strong> &mdash; "
                + HtmlReportRenderer.escapeHtml(reason) + "<br>\n"
                + "    " + remedy + "\n"
                + "  </div>\n"
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

    // ─────────────────────────────────────────────────────────────
    // Verdict token styling
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a small verdict badge for the report header.
     * Shows checkmark + PASS (green) or X + FAIL (red). Hidden for UNDECISIVE.
     */
    private static String buildVerdictBadge(String verdict) {
        if (verdict == null || "UNDECISIVE".equals(verdict)) return "";
        boolean pass = "PASS".equals(verdict);
        return "      <div class=\"header-verdict " + (pass ? "hv-pass" : "hv-fail") + "\">"
                + (pass ? "&#10003; " : "&#10007; ") + HtmlReportRenderer.escapeHtml(verdict)
                + "</div>\n";
    }

    /**
     * Injects {@code blockHtml} immediately after the first {@code </h2>} tag
     * so Java-computed data appears between the heading and the AI prose.
     */
    private static String injectAfterH2(String sectionHtml, String blockHtml) {
        int h2Close = sectionHtml.indexOf("</h2>");
        if (h2Close < 0) return blockHtml + sectionHtml;
        int insertAt = h2Close + "</h2>".length();
        return sectionHtml.substring(0, insertAt) + "\n" + blockHtml + sectionHtml.substring(insertAt);
    }

    /**
     * Post-processes AI-generated HTML to style standalone {@code PASS} and
     * {@code FAIL} verdict tokens with colour and bold weight.
     *
     * @param html AI-generated HTML from {@link #markdownToHtml}; may be null or blank
     * @return HTML with PASS/FAIL tokens styled; input returned unchanged if null or blank
     */
    static String styleVerdictTokens(String html) {
        if (html == null || html.isBlank()) return html;
        // Word-boundary match ensures "bypass", "PASSED", "FAILING" are not affected.
        // Replacement uses inline styles so the styling is self-contained within the
        // AI-generated panel content — no dependency on external CSS class names.
        html = html.replaceAll("\\bPASS\\b",
                "<span style=\"color:var(--color-success);font-weight:700\">PASS</span>");
        html = html.replaceAll("\\bFAIL\\b",
                "<span style=\"color:var(--color-danger);font-weight:700\">FAIL</span>");
        // Bold the four exact classification tokens. Literal alternation — no
        // generic pattern — so only the documented labels are ever matched.
        html = html.replaceAll(
                "\\b(THROUGHPUT-BOUND|LATENCY-BOUND|ERROR-BOUND|CAPACITY-WALL)\\b",
                "<strong>$1</strong>");
        return html;
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
        StringBuilder sb = new StringBuilder("<div class=\"tbl-wrap\"><table>\n<thead>\n<tr>");
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
        return sb.append("</tbody>\n</table></div>\n").toString();
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

    /**
     * Appends a metadata row to the {@code .meta-grid} as a {@code <span class="ml">} /
     * {@code <span class="mv">} pair. Skips blank or null values.
     *
     * @param sb        target string builder
     * @param label     visible label text (not escaped — must be a literal constant)
     * @param value     metadata value; skipped when null or blank
     * @param boldValue when {@code true}, emits {@code style="font-weight:700"} on the value span
     */
    private static void appendMetaRow(StringBuilder sb, String label, String value, boolean boldValue) {
        if (value == null || value.isBlank()) return;
        sb.append("  <span class=\"ml\">").append(label).append("</span>")
                .append("<span class=\"mv\"");
        if (boldValue) sb.append(" style=\"font-weight:700\"");
        sb.append(">").append(HtmlReportRenderer.escapeHtml(value)).append("</span>\n");
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
                .append("  function timeChart(id, data, label, unit, color, dash) {\n")
                .append("    var gridColor = document.querySelector('.rpt.dark')\n")
                .append("      ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.04)';\n")
                .append("    new Chart(document.getElementById(id), {\n")
                .append("      type: 'line',\n")
                .append("      data: { labels: labels, datasets: [{\n")
                .append("        label: label, data: data, borderColor: color,\n")
                .append("        backgroundColor: color.replace('1)', '0.10)'),\n")
                .append("        borderWidth: 2, pointRadius: 3, pointHoverRadius: 6,\n")
                .append("        fill: true, tension: 0.25, spanGaps: false,\n")
                .append("        borderDash: dash || []\n")
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
                .append("               grid: { color: gridColor } },\n")
                .append("          y: { beginAtZero: true,\n")
                .append("               title: { display: true, text: unit, font: { size: 11 } },\n")
                .append("               ticks: { font: { size: 11 } },\n")
                .append("               grid: { color: gridColor } }\n")
                .append("        }\n")
                .append("      }\n")
                .append("    });\n")
                .append("  }\n")
                .toString();
    }

    /**
     * Builds the inline JavaScript for sidebar navigation and Excel export.
     *
     * <h4>Sidebar navigation</h4>
     * <p>Each nav button stores the target panel's {@code id} in {@code data-panel}.
     * On click, all active classes are cleared and reapplied to the clicked button
     * and its panel. When the activated panel contains a {@code <canvas>} element,
     * all Chart.js instances are resized to fix the 0×0 canvas dimensions from
     * hidden initialisation. {@code Chart.instances} is a plain object in
     * Chart.js 4.x — iterated via {@code Object.values()}.</p>
     *
     * <h4>Excel export (SheetJS)</h4>
     * <p>One worksheet per panel, named by the panel's {@code data-title}.
     * Charts panels receive a placeholder message (canvases are not serialisable).
     * Table panels use {@code XLSX.utils.table_to_sheet}. Prose-only panels produce
     * a single wide text column.</p>
     *
     * @return self-executing {@code <script>} block string
     */
    private static String buildTabJs() {
        return new StringBuilder(2048)
                .append("<script>\n(function() {\n")
                // ── Sidebar switching ────────────────────────────────────────────
                .append("  document.querySelectorAll('.nav-item').forEach(function(btn) {\n")
                .append("    btn.addEventListener('click', function() {\n")
                .append("      document.querySelectorAll('.nav-item').forEach(function(b) { b.classList.remove('active'); });\n")
                .append("      document.querySelectorAll('.panel').forEach(function(p) { p.classList.remove('active'); });\n")
                .append("      btn.classList.add('active');\n")
                .append("      var el = document.getElementById(btn.dataset.panel);\n")
                .append("      if (el) el.classList.add('active');\n")
                // Chart resize: keyed on canvas presence, not data-charts attribute
                .append("      if (el && el.querySelector('canvas')) {\n")
                .append("        if (typeof Chart !== 'undefined') {\n")
                .append("          Object.values(Chart.instances).forEach(function(inst) { inst.resize(); });\n")
                .append("        } else {\n")
                .append("          el.querySelectorAll('.chart-canvas-wrap').forEach(function(w) {\n")
                .append("            w.innerHTML = '<p style=\"color:var(--color-text-secondary);font-size:12px;padding:20px\">")
                .append("Charts unavailable \\u2014 Chart.js library could not be loaded. Check your internet connection.</p>';\n")
                .append("          });\n")
                .append("        }\n")
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
                // ── Sheets 2–N: one per panel ────────────────────────────────────
                .append("    document.querySelectorAll('.panel').forEach(function(panel) {\n")
                .append("      var title = panel.dataset.title || 'Sheet';\n")
                .append("      var sheetName = title.substring(0, 31);\n")
                .append("      var ws;\n")
                // Charts panel: write a referral message — PNG charts require SheetJS Pro
                .append("      if (panel.querySelector('canvas')) {\n")
                .append("        ws = XLSX.utils.aoa_to_sheet([\n")
                .append("          ['Performance charts are not available in Excel export.'],\n")
                .append("          ['Refer to the HTML report or use Export PDF for interactive charts.']\n")
                .append("        ]);\n")
                .append("        ws['!cols'] = [{wch: 65}];\n")
                .append("      } else if (panel.querySelector('table')) {\n")
                .append("        try {\n")
                .append("          var tables = panel.querySelectorAll('table');\n")
                .append("          if (tables.length === 1) {\n")
                .append("            ws = XLSX.utils.table_to_sheet(tables[0]);\n")
                .append("          } else {\n")
                .append("            var aoa = [];\n")
                .append("            tables.forEach(function(tbl, idx) {\n")
                .append("              if (idx > 0) aoa.push([]);\n")
                .append("              tbl.querySelectorAll('tr').forEach(function(tr) {\n")
                .append("                var cells = [];\n")
                .append("                tr.querySelectorAll('th, td').forEach(function(c) { cells.push(c.textContent.trim()); });\n")
                .append("                aoa.push(cells);\n")
                .append("              });\n")
                .append("            });\n")
                .append("            ws = XLSX.utils.aoa_to_sheet(aoa);\n")
                .append("          }\n")
                .append("        } catch(ex) { ws = XLSX.utils.aoa_to_sheet([['Table export failed: ' + ex.message]]); }\n")
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
     * Builds the inline JavaScript for the Transaction Metrics table features:
     * search filtering, client-side pagination, and click-to-sort columns.
     *
     * <h4>Search</h4>
     * <p>Case-insensitive substring match on the transaction name column (index 0).
     * Non-matching rows receive a {@code data-hide="1"} attribute and are excluded
     * from pagination. Resets to page 1 on every keystroke.</p>
     *
     * <h4>Pagination</h4>
     * <p>Dropdown selection (10/25/50/100) controls how many rows are visible per
     * page. Page buttons are dynamically rendered with ellipsis for large page
     * counts. Prev/Next arrow buttons provided.</p>
     *
     * <h4>Column sort</h4>
     * <p>Clicking any {@code <th>} toggles ascending/descending sort. Numeric
     * columns are compared as floats (after stripping {@code %} and {@code ,}).
     * Text columns (transaction name, SLA status) use locale-aware string
     * comparison. Active sort direction is shown via CSS classes
     * ({@code .sort-asc} / {@code .sort-desc}).</p>
     *
     * <p>Gracefully no-ops when the metrics table is absent (empty row list).</p>
     *
     * @return self-executing {@code <script>} block string
     */
    private static String buildMetricsJs() {
        return new StringBuilder(3072)
                .append("<script>\n(function() {\n")
                .append("  var tbl = document.getElementById('metricsTable');\n")
                .append("  if (!tbl) return;\n")
                .append("  var tbody = tbl.querySelector('tbody');\n")
                .append("  var allRows = Array.from(tbody.querySelectorAll('tr'));\n")
                .append("  var searchIn = document.getElementById('metricsSearch');\n")
                .append("  var pageSel = document.getElementById('metricsPageSize');\n")
                .append("  var info = document.getElementById('metricsInfo');\n")
                .append("  var pgDiv = document.getElementById('metricsPages');\n")
                .append("  var curPage = 1, pgSize = parseInt(pageSel.value, 10);\n")
                .append("  var totalRowCount = allRows.length;\n")
                .append("  var debounceTimer = null;\n\n")
                // ── visible rows (excluding search-hidden) ────────────────────
                .append("  function vis() {\n")
                .append("    return allRows.filter(function(r) { return r.dataset.hide !== '1'; });\n")
                .append("  }\n\n")
                // ── render: apply pagination to visible rows ──────────────────
                .append("  function render() {\n")
                .append("    var v = vis(), total = v.length;\n")
                .append("    var tp = Math.max(1, Math.ceil(total / pgSize));\n")
                .append("    if (curPage > tp) curPage = tp;\n")
                .append("    var s = (curPage - 1) * pgSize, e = Math.min(s + pgSize, total);\n")
                .append("    allRows.forEach(function(r) { r.style.display = 'none'; });\n")
                .append("    for (var i = s; i < e; i++) v[i].style.display = '';\n")
                // info label
                .append("    var infoText = total === 0 ? 'No matching transactions'\n")
                .append("      : 'Showing ' + (s + 1) + '\\u2013' + e + ' of ' + total + ' transactions';\n")
                .append("    if (total < totalRowCount) infoText += ' (filtered from ' + totalRowCount + ' total)';\n")
                .append("    info.textContent = infoText;\n")
                // page buttons
                .append("    pgDiv.innerHTML = '';\n")
                // prev
                .append("    addBtn('\\u25C0', curPage <= 1, function() { curPage--; render(); });\n")
                // numbered pages
                .append("    pgRange(curPage, tp).forEach(function(p) {\n")
                .append("      if (p === 0) {\n")
                .append("        var sp = document.createElement('span');\n")
                .append("        sp.textContent = '\\u2026'; sp.style.cssText = 'padding:4px;font-size:11px';\n")
                .append("        pgDiv.appendChild(sp);\n")
                .append("      } else {\n")
                .append("        var b = addBtn(p, false, (function(pg) {\n")
                .append("          return function() { curPage = pg; render(); };\n")
                .append("        })(p));\n")
                .append("        if (p === curPage) b.classList.add('active');\n")
                .append("      }\n")
                .append("    });\n")
                // next
                .append("    addBtn('\\u25B6', curPage >= tp, function() { curPage++; render(); });\n")
                .append("  }\n\n")
                // ── helper: create and append a page button ───────────────────
                .append("  function addBtn(txt, dis, fn) {\n")
                .append("    var b = document.createElement('button');\n")
                .append("    b.textContent = txt; b.disabled = dis; b.onclick = fn;\n")
                .append("    pgDiv.appendChild(b); return b;\n")
                .append("  }\n\n")
                // ── smart page range with ellipsis (0 = ellipsis) ─────────────
                .append("  function pgRange(c, t) {\n")
                .append("    if (t <= 7) { var a=[]; for (var i=1;i<=t;i++) a.push(i); return a; }\n")
                .append("    var p = [1];\n")
                .append("    if (c > 3) p.push(0);\n")
                .append("    for (var i=Math.max(2,c-1); i<=Math.min(t-1,c+1); i++) p.push(i);\n")
                .append("    if (c < t-2) p.push(0);\n")
                .append("    p.push(t); return p;\n")
                .append("  }\n\n")
                // ── search: filter by transaction name ────────────────────────
                .append("  searchIn.addEventListener('input', function() {\n")
                .append("    clearTimeout(debounceTimer);\n")
                .append("    var self = this;\n")
                .append("    debounceTimer = setTimeout(function() {\n")
                .append("      var q = self.value.toLowerCase();\n")
                .append("      allRows.forEach(function(r) {\n")
                .append("        var tx = r.cells[0] ? r.cells[0].textContent.toLowerCase() : '';\n")
                .append("        r.dataset.hide = tx.indexOf(q) === -1 ? '1' : '';\n")
                .append("      });\n")
                .append("      curPage = 1; render();\n")
                .append("    }, 200);\n")
                .append("  });\n\n")
                // ── page-size change ──────────────────────────────────────────
                .append("  pageSel.addEventListener('change', function() {\n")
                .append("    pgSize = parseInt(this.value, 10); curPage = 1; render();\n")
                .append("  });\n\n")
                // ── column sort ───────────────────────────────────────────────
                .append("  var ths = tbl.querySelectorAll('thead th');\n")
                .append("  ths.forEach(function(th, col) {\n")
                .append("    th.addEventListener('click', function() {\n")
                .append("      var asc = !th.classList.contains('sort-asc');\n")
                .append("      ths.forEach(function(h) { h.classList.remove('sort-asc','sort-desc'); });\n")
                .append("      th.classList.add(asc ? 'sort-asc' : 'sort-desc');\n")
                .append("      var hint = document.getElementById('sortHint'); if (hint) hint.style.display = 'none';\n")
                .append("      allRows.sort(function(a, b) {\n")
                .append("        var at = a.cells[col] ? a.cells[col].textContent.trim() : '';\n")
                .append("        var bt = b.cells[col] ? b.cells[col].textContent.trim() : '';\n")
                .append("        var an = parseFloat(at.replace(/[,%]/g, ''));\n")
                .append("        var bn = parseFloat(bt.replace(/[,%]/g, ''));\n")
                .append("        if (!isNaN(an) && !isNaN(bn)) return asc ? an-bn : bn-an;\n")
                .append("        return asc ? at.localeCompare(bt) : bt.localeCompare(at);\n")
                .append("      });\n")
                .append("      allRows.forEach(function(r) { tbody.appendChild(r); });\n")
                .append("      curPage = 1; render();\n")
                .append("    });\n")
                .append("  });\n\n")
                // ── initial render ────────────────────────────────────────────
                .append("  render();\n")
                .append("})();\n</script>\n")
                .toString();
    }

    /**
     * Builds the inline JavaScript for the dark mode toggle button.
     *
     * <p>Toggles the {@code .dark} CSS class on the {@code .rpt} root element
     * and updates the button label between "Dark Mode" and "Light Mode".
     * State is not persisted — defaults to light mode on every page load.</p>
     *
     * @return self-executing {@code <script>} block string
     */
    private static String buildDarkModeJs() {
        return "<script>\n"
                + "(function() {\n"
                + "  var rpt = document.querySelector('.rpt');\n"
                + "  var btn = document.getElementById('darkToggle');\n"
                + "  function updateChartGrids(isDark) {\n"
                + "    if (typeof Chart === 'undefined') return;\n"
                + "    var g = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.04)';\n"
                + "    Object.values(Chart.instances).forEach(function(c) {\n"
                + "      if (c.options.scales && c.options.scales.x) c.options.scales.x.grid.color = g;\n"
                + "      if (c.options.scales && c.options.scales.y) c.options.scales.y.grid.color = g;\n"
                + "      c.update('none');\n"
                + "    });\n"
                + "  }\n"
                + "  if (localStorage.getItem('jaar-dark') === '1') {\n"
                + "    rpt.classList.add('dark');\n"
                + "    btn.innerHTML = '&#9728;&nbsp; Light Mode';\n"
                + "    updateChartGrids(true);\n"
                + "  }\n"
                + "  window.toggleDark = function() {\n"
                + "    rpt.classList.toggle('dark');\n"
                + "    var isDark = rpt.classList.contains('dark');\n"
                + "    btn.innerHTML = isDark ? '&#9728;&nbsp; Light Mode' : '&#127769;&nbsp; Dark Mode';\n"
                + "    localStorage.setItem('jaar-dark', isDark ? '1' : '0');\n"
                + "    updateChartGrids(isDark);\n"
                + "  };\n"
                + "})();\n"
                + "</script>\n";
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
        return "  <style>\n"
                // ── Custom properties ─────────────────────────────────────────
                + "    :root {\n"
                + "      --color-text-primary:         #1a202c;\n"
                + "      --color-text-secondary:       #4a5568;\n"
                + "      --color-text-tertiary:        #718096;\n"
                + "      --color-background-primary:   #ffffff;\n"
                + "      --color-background-secondary: #f7fafc;\n"
                + "      --color-background-tertiary:  #f0f4f8;\n"
                + "      --color-border-secondary:     #cbd5e0;\n"
                + "      --color-border-tertiary:      #e2e8f0;\n"
                + "      --color-accent:               #3182ce;\n"
                + "      --color-success:              #276749;\n"
                + "      --color-danger:               #c53030;\n"
                + "    }\n"
                + "    .dark {\n"
                + "      --color-text-primary:         #e2e8f0;\n"
                + "      --color-text-secondary:       #a0aec0;\n"
                + "      --color-text-tertiary:        #718096;\n"
                + "      --color-background-primary:   #1a202c;\n"
                + "      --color-background-secondary: #2d3748;\n"
                + "      --color-background-tertiary:  #171923;\n"
                + "      --color-border-secondary:     #4a5568;\n"
                + "      --color-border-tertiary:      #2d3748;\n"
                + "      --color-accent:               #63b3ed;\n"
                + "      --color-success:              #68d391;\n"
                + "      --color-danger:               #fc8181;\n"
                + "    }\n"
                + "    .dark .rpt-header { background: #0d1b2a; }\n"
                + "    .dark .sidebar { background: #171923; border-right-color: #2d3748; }\n"
                + "    .dark .nav-item { color: #a0aec0; }\n"
                + "    .dark .nav-item:hover { background: #2d3748; color: #e2e8f0; }\n"
                + "    .dark .nav-item.active { background: #1a202c; color: #63b3ed; border-left-color: #63b3ed; }\n"
                + "    .dark th { background: #1a202c; color: #e2e8f0; }\n"
                + "    .dark code { background: #2d3748; color: #fc8181; }\n"
                + "    .dark pre { background: #171923; color: #e2e8f0; }\n"
                + "    .dark blockquote { background: #1a3a5c; border-left-color: #3182ce; }\n"
                + "    .dark .charts-warn { background: #2d2a1a; border-color: #d69e2e; color: #fefcbf; }\n"
                + "    .dark .chart-box { background: #2d3748; border-color: #4a5568; }\n"
                + "    .dark .chart-box h3 { color: #a0aec0; }\n"
                + "    .dark .verdict-banner { background: #1a3a2a; border-color: #276749; }\n"
                + "    .dark .verdict-banner.fail { background: #3a1a1a; border-color: #c53030; }\n"
                + "    .dark .verdict-desc { color: #c6f6d5; }\n"
                + "    .dark .no-err { background: #1a3a2a; border-color: #276749; }\n"
                + "    .dark .no-err-icon { background: #276749; }\n"
                + "    .dark .no-err-text { color: #c6f6d5; }\n"
                + "    .dark .ai-notice { background: #2d2a1a; border-color: #d69e2e; color: #fefcbf; }\n"
                + "    .dark .kpi { background: #2d3748; border-color: #4a5568; }\n"
                + "    .dark h3 { color: var(--color-text-primary); }\n"
                + "    .dark #metricsSearch { background: #2d3748; color: #e2e8f0; border-color: #4a5568; }\n"
                + "    .dark #metricsPageSize { background: #2d3748; color: #e2e8f0; border-color: #4a5568; }\n"
                + "    .dark #metricsPages button.active { background: #63b3ed; color: #1a202c; border-color: #63b3ed; }\n"
                // ── Reset ─────────────────────────────────────────────────────
                + "    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }\n"
                + "    html, body { height: 100%; }\n"
                // ── Root wrapper ─────────────────────────────────────────────
                + "    .rpt {\n"
                + "      font-family: 'Segoe UI', system-ui, sans-serif; font-size: 14px;\n"
                + "      line-height: 1.7; color: var(--color-text-primary);\n"
                + "      background: var(--color-background-tertiary);\n"
                + "      display: flex; flex-direction: column; min-height: 100vh;\n"
                + "    }\n"
                // ── Header ───────────────────────────────────────────────────
                + "    .rpt-header {\n"
                + "      background: #1a365d; color: white; padding: 24px 32px;\n"
                + "      display: flex; align-items: flex-start; justify-content: space-between;\n"
                + "      gap: 24px; flex-shrink: 0;\n"
                + "    }\n"
                + "    .rpt-header h1 { font-size: 22px; font-weight: 600; margin-bottom: 4px; color: white; }\n"
                + "    .rpt-header .sub { font-size: 13px; color: rgba(255,255,255,0.65); margin-bottom: 14px; }\n"
                + "    .meta-grid { display: grid; grid-template-columns: auto 1fr; gap: 2px 16px; font-size: 12px; }\n"
                + "    .ml { color: rgba(255,255,255,0.6); font-weight: 500; white-space: nowrap; }\n"
                + "    .mv { color: white; }\n"
                // ── Header action buttons ────────────────────────────────────
                + "    .header-actions { display: flex; flex-direction: column; gap: 6px; align-items: flex-end; flex-shrink: 0; }\n"
                + "    .exp-btn {\n"
                + "      width: 148px; padding: 7px 0;\n"
                + "      border: 1px solid rgba(255,255,255,0.35); background: rgba(255,255,255,0.10);\n"
                + "      cursor: pointer; border-radius: 5px; font-size: 13px; font-family: inherit;\n"
                + "      color: white; white-space: nowrap; line-height: 1.3; text-align: center;\n"
                + "    }\n"
                + "    .exp-btn:hover { background: rgba(255,255,255,0.22); }\n"
                // ── Body row (sidebar + main column) ─────────────────────────
                + "    .body-row { display: flex; align-items: stretch; flex: 1; }\n"
                // ── Sidebar ──────────────────────────────────────────────────
                + "    .sidebar {\n"
                + "      width: 200px; flex-shrink: 0; background: #f0f4f8;\n"
                + "      border-right: 1px solid #cbd5e0; padding: 12px 0;\n"
                + "      position: sticky; top: 0; align-self: flex-start;\n"
                + "      height: 100vh; overflow-y: auto;\n"
                + "    }\n"
                + "    .nav-item {\n"
                + "      display: block; width: 100%; padding: 9px 20px; border: none;\n"
                + "      background: transparent; text-align: left; font-size: 13px;\n"
                + "      font-family: inherit; color: #4a5568; cursor: pointer;\n"
                + "      line-height: 1.4; border-left: 3px solid transparent;\n"
                + "    }\n"
                + "    .nav-item:hover  { background: #e2e8f0; color: #2d3748; }\n"
                + "    .nav-item.active { background: #fff; color: #1a365d; font-weight: 600; border-left: 3px solid #1a365d; }\n"
                // ── Main column ───────────────────────────────────────────────
                + "    .main-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }\n"
                + "    .content-area { flex: 1; padding: 28px 32px; background: var(--color-background-primary); overflow: auto; }\n"
                // ── Panels ───────────────────────────────────────────────────
                + "    .panel       { display: none; }\n"
                + "    .panel.active { display: block; }\n"
                // ── Typography ───────────────────────────────────────────────
                + "    h2 {\n"
                + "      font-size: 16px; font-weight: 600; color: var(--color-text-primary);\n"
                + "      border-left: 3px solid var(--color-accent); padding-left: 11px; margin: 0 0 16px;\n"
                + "    }\n"
                + "    h3 { font-size: 13px; font-weight: 600; color: #2d3748; margin: 18px 0 8px; }\n"
                + "    p  { margin-bottom: 12px; font-size: 13px; color: var(--color-text-primary); }\n"
                // ── Tables ───────────────────────────────────────────────────
                + "    .tbl-wrap { overflow-x: auto; margin: 12px 0 20px; border-radius: 6px; border: 0.5px solid var(--color-border-secondary); }\n"
                + "    table { border-collapse: collapse; width: 100%; background: var(--color-background-primary); font-size: 12px; }\n"
                + "    th {\n"
                + "      background: #2d3748; color: white; padding: 8px 12px;\n"
                + "      text-align: left; font-size: 11px; font-weight: 600;\n"
                + "      text-transform: uppercase; letter-spacing: 0.4px; white-space: nowrap;\n"
                + "    }\n"
                + "    td { padding: 7px 12px; border-bottom: 0.5px solid var(--color-border-tertiary); white-space: nowrap; }\n"
                + "    td.num { text-align: right; font-variant-numeric: tabular-nums; }\n"
                + "    td.sla-pass { text-align: center; font-weight: 600; color: var(--color-success); }\n"
                + "    td.sla-fail { text-align: center; font-weight: 600; color: var(--color-danger); }\n"
                + "    td.sla-na   { text-align: center; color: #a0aec0; }\n"
                + "    tr:last-child td  { border-bottom: none; }\n"
                + "    tr:nth-child(even) td { background: var(--color-background-secondary); }\n"
                // ── Inline elements ───────────────────────────────────────────
                + "    code { background: #edf2f7; padding: 2px 6px; border-radius: 4px; font-family: Consolas, monospace; font-size: 12px; color: #c53030; }\n"
                + "    pre  { background: #2d3748; color: #e2e8f0; padding: 14px 18px; border-radius: 6px; overflow-x: auto; margin: 12px 0 18px; }\n"
                + "    pre code { background: none; color: inherit; padding: 0; }\n"
                + "    blockquote { border-left: 4px solid #63b3ed; margin: 14px 0; padding: 8px 14px; background: #ebf8ff; border-radius: 0 6px 6px 0; }\n"
                + "    ul, ol { margin: 8px 0 14px 22px; font-size: 13px; }\n"
                + "    li { margin-bottom: 5px; }\n"
                + "    strong { font-weight: 700; }\n"
                // ── AI notice ─────────────────────────────────────────────────
                + "    .ai-notice {\n"
                + "      display: flex; align-items: center; gap: 8px;\n"
                + "      background: #fffbeb; border: 1px solid #f6e05e;\n"
                + "      border-radius: 6px; padding: 8px 14px;\n"
                + "      font-size: 12px; color: #744210; margin-bottom: 20px;\n"
                + "    }\n"
                // ── KPI cards ─────────────────────────────────────────────────
                + "    .kpi-grid { display: grid; grid-template-columns: repeat(5, minmax(0,1fr)); gap: 12px; margin-bottom: 24px; }\n"
                + "    .kpi { background: var(--color-background-secondary); border-radius: 8px; padding: 14px 16px; border: 1px solid var(--color-border-tertiary); }\n"
                + "    .kpi-label { font-size: 11px; color: var(--color-text-secondary); margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.4px; }\n"
                + "    .kpi-value { font-size: 20px; font-weight: 500; color: var(--color-text-primary); line-height: 1.2; }\n"
                + "    .kpi-value.pass { color: var(--color-success); font-weight: 700; }\n"
                + "    .kpi-value.fail { color: var(--color-danger); font-weight: 700; }\n"
                // ── Verdict banner ────────────────────────────────────────────
                + "    .verdict-banner {\n"
                + "      display: flex; align-items: center; gap: 20px;\n"
                + "      background: #f0fff4; border: 1.5px solid #9ae6b4;\n"
                + "      border-radius: 10px; padding: 18px 22px; margin-bottom: 24px;\n"
                + "    }\n"
                + "    .verdict-banner.fail { background: #fff5f5; border-color: #feb2b2; }\n"
                + "    .verdict-badge-sm     { font-size: 26px; font-weight: 700; color: var(--color-success); flex-shrink: 0; }\n"
                + "    .verdict-badge-sm.fail { color: var(--color-danger); }\n"
                + "    .verdict-desc  { font-size: 13px; color: #22543d; line-height: 1.6; }\n"
                + "    .verdict-desc strong { font-weight: 600; }\n"
                // ── No-error box ──────────────────────────────────────────────
                + "    .no-err {\n"
                + "      display: flex; align-items: center; gap: 12px;\n"
                + "      background: #f0fff4; border: 1px solid #9ae6b4;\n"
                + "      border-radius: 8px; padding: 16px 20px;\n"
                + "    }\n"
                + "    .no-err-icon {\n"
                + "      width: 32px; height: 32px; border-radius: 50%;\n"
                + "      background: #c6f6d5; display: flex; align-items: center;\n"
                + "      justify-content: center; font-size: 16px; flex-shrink: 0;\n"
                + "    }\n"
                + "    .no-err-text { font-size: 13px; color: #22543d; font-weight: 500; }\n"
                // ── Verdict panel (large stamp) ───────────────────────────────
                + "    .verdict-pass { font-size: 64px; font-weight: 700; color: var(--color-success); line-height: 1; margin-bottom: 8px; }\n"
                + "    .verdict-fail { font-size: 64px; font-weight: 700; color: var(--color-danger); line-height: 1; margin-bottom: 8px; }\n"
                // ── Error breakdown table ────────────────────────────────────
                + "    .error-breakdown { margin: 20px 0 24px; }\n"
                + "    .error-breakdown h3 { font-size: 14px; font-weight: 600; margin: 0 0 8px; color: var(--color-text-primary); }\n"
                + "    .data-table { width: 100%; border-collapse: collapse; font-size: 12px; }\n"
                + "    .data-table th { text-align: left; padding: 6px 10px; background: var(--color-background-secondary);\n"
                + "      border-bottom: 2px solid var(--color-border-secondary); font-weight: 600; font-size: 11px;\n"
                + "      color: var(--color-text-secondary); text-transform: uppercase; letter-spacing: 0.3px; }\n"
                + "    .data-table td { padding: 5px 10px; border-bottom: 1px solid var(--color-border-secondary); }\n"
                + "    .data-table .num { text-align: right; font-variant-numeric: tabular-nums; }\n"
                // ── Latency panel ────────────────────────────────────────────
                + "    .latency-panel { margin: 20px 0 24px; }\n"
                + "    .latency-panel h3 { font-size: 14px; font-weight: 600; margin: 0 0 10px; color: var(--color-text-primary); }\n"
                + "    .latency-cards { display: flex; gap: 16px; flex-wrap: wrap; }\n"
                + "    .latency-card {\n"
                + "      flex: 1; min-width: 160px; padding: 16px; border-radius: 8px;\n"
                + "      background: var(--color-background-secondary); border: 1px solid var(--color-border-secondary);\n"
                + "      cursor: help;\n"
                + "    }\n"
                + "    .latency-label { font-size: 11px; font-weight: 600; color: var(--color-text-secondary);\n"
                + "      text-transform: uppercase; letter-spacing: 0.3px; margin-bottom: 6px; }\n"
                + "    .latency-value { font-size: 28px; font-weight: 700; color: var(--color-text-primary); }\n"
                + "    .info-icon { font-size: 12px; opacity: 0.5; vertical-align: middle; }\n"
                // ── Section-specific ─────────────────────────────────────────
                + "    .metrics-section { margin: 40px 0 0; }\n"
                + "    .metrics-toolbar {\n"
                + "      display: flex; align-items: center; justify-content: space-between;\n"
                + "      gap: 12px; margin-bottom: 10px;\n"
                + "    }\n"
                + "    #metricsSearch {\n"
                + "      width: 240px; padding: 6px 10px;\n"
                + "      border: 1px solid var(--color-border-secondary); border-radius: 5px;\n"
                + "      font-size: 12px; font-family: inherit; outline: none;\n"
                + "    }\n"
                + "    #metricsSearch:focus { border-color: var(--color-accent); box-shadow: 0 0 0 2px rgba(49,130,206,0.2); }\n"
                + "    #metricsPageSize {\n"
                + "      padding: 4px 6px; border: 1px solid var(--color-border-secondary);\n"
                + "      border-radius: 4px; font-size: 12px; font-family: inherit; margin: 0 4px;\n"
                + "    }\n"
                + "    .metrics-toolbar-right label { font-size: 12px; color: var(--color-text-secondary); }\n"
                + "    .metrics-paging {\n"
                + "      display: flex; align-items: center; justify-content: space-between;\n"
                + "      margin-top: 10px; font-size: 12px; color: var(--color-text-secondary);\n"
                + "    }\n"
                + "    #metricsPages { display: flex; gap: 4px; flex-wrap: wrap; }\n"
                + "    #metricsPages button {\n"
                + "      min-width: 28px; padding: 4px 8px;\n"
                + "      border: 1px solid var(--color-border-secondary); border-radius: 4px;\n"
                + "      background: var(--color-background-primary); cursor: pointer;\n"
                + "      font-size: 11px; font-family: inherit; color: var(--color-text-secondary);\n"
                + "    }\n"
                + "    #metricsPages button:hover { background: var(--color-background-tertiary); }\n"
                + "    #metricsPages button.active {\n"
                + "      background: #1a365d; color: white; border-color: #1a365d;\n"
                + "    }\n"
                + "    #metricsPages button:disabled { opacity: 0.4; cursor: default; }\n"
                + "    #metricsTable th { cursor: pointer; user-select: none; }\n"
                + "    #metricsTable th::after {\n"
                + "      content: ' \\21C5'; font-size: 9px; opacity: 0.35;\n"
                + "    }\n"
                + "    #metricsTable th.sort-asc::after {\n"
                + "      content: ' \\25B2'; opacity: 0.9;\n"
                + "    }\n"
                + "    #metricsTable th.sort-desc::after {\n"
                + "      content: ' \\25BC'; opacity: 0.9;\n"
                + "    }\n"
                + "    .charts-section  { margin: 0; }\n"
                + "    .charts-note { font-size: 11px; color: var(--color-text-tertiary); margin-bottom: 16px; }\n"
                + "    .charts-warn { background: #fffbeb; border: 1px solid #f6e05e; border-radius: 6px; padding: 12px 16px; color: #744210; font-size: 13px; }\n"
                + "    .charts-warn strong { font-weight: 600; }\n"
                // ── Charts grid (2 columns) ───────────────────────────────────
                + "    .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin-top: 4px; }\n"
                + "    .chart-box { background: var(--color-background-secondary); border: 1px solid var(--color-border-tertiary); border-radius: 8px; padding: 16px 18px 12px; }\n"
                + "    .chart-box h3 { font-size: 12px; font-weight: 600; color: var(--color-text-secondary); margin: 0 0 12px; text-transform: uppercase; letter-spacing: 0.4px; border: none; padding: 0; }\n"
                + "    .chart-canvas-wrap { position: relative; height: 220px; }\n"
                // ── Footer ───────────────────────────────────────────────────
                + "    .footer-rpt {\n"
                + "      font-size: 11px; color: var(--color-text-tertiary); padding: 12px 32px;\n"
                + "      border-top: 1px solid var(--color-border-tertiary);\n"
                + "      background: var(--color-background-primary); flex-shrink: 0;\n"
                + "    }\n"
                // ── Header verdict badge ───────────────────────────────────
                + "    .header-verdict {\n"
                + "      display: inline-block; padding: 4px 14px; border-radius: 4px;\n"
                + "      font-size: 14px; font-weight: 700; letter-spacing: 0.5px; margin: 8px 0 4px;\n"
                + "    }\n"
                + "    .hv-pass { background: rgba(72,187,120,0.2); color: #c6f6d5; }\n"
                + "    .hv-fail { background: rgba(245,101,101,0.2); color: #fed7d7; }\n"
                // ── Sort hint ─────────────────────────────────────────────────
                + "    .sort-hint { font-size: 11px; color: var(--color-text-tertiary); margin-bottom: 6px; }\n"
                + "    .nav-item[data-status=\"pass\"]:not(.active) { color: var(--color-success); }\n"
                + "    .nav-item[data-status=\"fail\"]:not(.active) { color: var(--color-danger); }\n"
                + "    .nav-item[data-status=\"warn\"]:not(.active) { color: #b7791f; }\n"
                + "    .dark .nav-item[data-status=\"warn\"]:not(.active) { color: #f6e05e; }\n"
                // ── Print styles ──────────────────────────────────────────────
                + "    @media print {\n"
                + "      .sidebar { display: none !important; }\n"
                + "      .header-actions { display: none !important; }\n"
                + "      .panel { display: block !important; page-break-inside: avoid; }\n"
                + "      .charts-grid { grid-template-columns: 1fr !important; }\n"
                + "      .body-row { display: block !important; }\n"
                + "      .metrics-toolbar, .metrics-paging { display: none !important; }\n"
                + "      #metricsTable tbody tr { display: table-row !important; }\n"
                + "    }\n"
                + "  </style>\n";
    }
}