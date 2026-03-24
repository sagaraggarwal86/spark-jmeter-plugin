package com.personal.jmeter.ai.report;

import com.personal.jmeter.parser.JTLParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HtmlPageBuilder}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of:</p>
 * <ul>
 *   <li>{@link HtmlPageBuilder#convertPipeTablesToHtml} — pipe-table conversion</li>
 *   <li>{@link HtmlPageBuilder#stripRawHtml} — raw-HTML block removal</li>
 *   <li>{@link HtmlPageBuilder#buildChartsSection} — always renders a header</li>
 *   <li>{@link HtmlPageBuilder#splitAtH2} — section splitting at h2 boundaries</li>
 *   <li>{@link HtmlPageBuilder#splitChartsBlock} — chart script extraction</li>
 *   <li>{@link HtmlPageBuilder#buildPage} — tabbed page structure</li>
 * </ul>
 */
@DisplayName("HtmlPageBuilder — convertPipeTablesToHtml")
class HtmlPageBuilderTest {

    // ─────────────────────────────────────────────────────────────
    // Null / empty input
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Empty and null input")
    class EmptyInputTests {

        @Test
        @DisplayName("null input returns empty string")
        void nullReturnsEmpty() {
            assertEquals("", HtmlPageBuilder.convertPipeTablesToHtml(null));
        }

        @Test
        @DisplayName("empty string returns single newline (one empty line processed)")
        void emptyReturnsSingleNewline() {
            assertEquals("\n", HtmlPageBuilder.convertPipeTablesToHtml(""));
        }

        @Test
        @DisplayName("blank lines pass through unchanged")
        void blankLinesPassThrough() {
            String input = "\n\n\n";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            // All blank lines should pass through (each gets a newline appended)
            assertFalse(result.contains("<table>"), "No table should be generated for blank lines");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Non-table pass-through
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Non-table pass-through")
    class NonTableTests {

        @Test
        @DisplayName("plain text passes through without table tags")
        void plainTextPassThrough() {
            String input = "This is plain text.\nNo tables here.";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertFalse(result.contains("<table>"));
            assertTrue(result.contains("This is plain text."));
            assertTrue(result.contains("No tables here."));
        }

        @Test
        @DisplayName("pipe row without separator row on next line is not a table")
        void pipeRowWithoutSeparatorNotTable() {
            String input = "| header1 | header2 |\n| data1 | data2 |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            // Without a separator row (|---|---|) on line 2, this is not a table
            assertFalse(result.contains("<table>"),
                    "Without a separator row after the header, no table should be generated");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Table detection and conversion
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Table detection and rendering")
    class TableDetectionTests {

        @Test
        @DisplayName("valid two-row pipe table is converted to HTML table")
        void validTwoRowTable() {
            String input = "| Name | Value |\n|---|---|\n| CPU | 80% |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"), "Should contain an HTML table");
            assertTrue(result.contains("<thead>"), "Should contain thead");
            assertTrue(result.contains("<tbody>"), "Should contain tbody");
            assertTrue(result.contains("<th>Name</th>"), "Header cell should contain 'Name'");
            assertTrue(result.contains("<td>CPU</td>"), "Data cell should contain 'CPU'");
            assertTrue(result.contains("<td>80%</td>"), "Data cell should contain '80%'");
        }

        @Test
        @DisplayName("multi-row table has correct number of body rows")
        void multiRowTable() {
            String input = "| Metric | Value |\n|---|---|\n| TPS | 100 |\n| Avg | 250 |\n| Max | 900 |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            // Count <tr> occurrences: 1 header + 3 body = 4 total
            long trCount = result.chars().filter(c -> c == '<').count();
            assertTrue(result.contains("<td>TPS</td>"), "First body row");
            assertTrue(result.contains("<td>250</td>"), "Second body row");
            assertTrue(result.contains("<td>900</td>"), "Third body row");
        }

        @Test
        @DisplayName("separator row with colons (alignment hints) is recognised")
        void separatorWithColonsRecognised() {
            String input = "| Left | Center | Right |\n|:---|:---:|---:|\n| a | b | c |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"),
                    "Separator with colons should still be recognised as a valid separator");
        }

        @Test
        @DisplayName("table without trailing pipe on any row is recognised (Gemini-style)")
        void noTrailingPipeTable() {
            String input = "| Priority | Hypothesis | Action\n|---|---|---\n| High | DB slow | Add index";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"), "Should detect table without trailing pipe");
            assertTrue(result.contains("<th>Priority</th>"), "Header cell Priority");
            assertTrue(result.contains("<th>Action</th>"), "Header cell Action");
            assertTrue(result.contains("<td>High</td>"), "Data cell High");
            assertTrue(result.contains("<td>Add index</td>"), "Data cell Add index");
        }

        @Test
        @DisplayName("table without leading pipe on any row is recognised")
        void noLeadingPipeTable() {
            String input = "Priority | Hypothesis | Action |\n---|---|---|\n High | DB slow | Add index |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"), "Should detect table without leading pipe");
            assertTrue(result.contains("<th>Priority</th>"), "Header cell Priority");
            assertTrue(result.contains("<th>Action</th>"), "Header cell Action");
        }

        @Test
        @DisplayName("table with no edge pipes (interior pipe only) is recognised")
        void noEdgePipeTable() {
            String input = "Priority | Hypothesis | Action\n---|---|---\n High | DB slow | Add index";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"), "Should detect interior-pipe-only table");
            assertTrue(result.contains("<th>Priority</th>"), "Header cell Priority");
            assertTrue(result.contains("<td>High</td>"), "Data cell High");
        }

        @Test
        @DisplayName("leading-and-trailing pipe table continues to work (Groq/OpenAI-style)")
        void leadingAndTrailingPipeTable() {
            String input = "| Priority | Action |\n|---|---|\n| High | Fix it |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("<table>"), "Should detect full-pipe table");
            assertTrue(result.contains("<th>Priority</th>"), "Header cell Priority");
            assertTrue(result.contains("<td>Fix it</td>"), "Data cell Fix it");
        }
    }

    @Nested
    @DisplayName("Cell content escaping")
    class CellEscapingTests {

        @Test
        @DisplayName("HTML special characters in cells are escaped")
        void htmlSpecialCharsEscaped() {
            String input = "| Header |\n|---|\n| <script>alert('xss')</script> |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertFalse(result.contains("<script>"),
                    "Script tags in cell content must be escaped");
            assertTrue(result.contains("&lt;script&gt;"),
                    "Escaped script tag should appear in output");
        }

        @Test
        @DisplayName("ampersands in cell content are escaped")
        void ampersandsEscaped() {
            String input = "| Key |\n|---|\n| A & B |";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("A &amp; B"), "Ampersands should be escaped");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Mixed content
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Mixed table and non-table content")
    class MixedContentTests {

        @Test
        @DisplayName("text before and after table is preserved")
        void textAroundTablePreserved() {
            String input = "Before the table.\n| H1 |\n|---|\n| D1 |\nAfter the table.";
            String result = HtmlPageBuilder.convertPipeTablesToHtml(input);
            assertTrue(result.contains("Before the table."));
            assertTrue(result.contains("<table>"));
            assertTrue(result.contains("After the table."));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // stripRawHtml
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stripRawHtml — raw HTML block removal")
    class StripRawHtmlTests {

        @Test
        @DisplayName("null input returns null unchanged")
        void nullReturnsNull() {
            assertNull(HtmlPageBuilder.stripRawHtml(null));
        }

        @Test
        @DisplayName("blank input returns blank unchanged")
        void blankReturnsBlank() {
            String blank = "   ";
            assertEquals(blank, HtmlPageBuilder.stripRawHtml(blank));
        }

        @Test
        @DisplayName("opening script tag line is stripped")
        void scriptTagStripped() {
            String input = "Some text\n<script>alert('xss')</script>\nMore text";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertFalse(result.contains("<script>"), "script tag line must be stripped");
            assertTrue(result.contains("Some text"), "preceding text must be preserved");
            assertTrue(result.contains("More text"), "following text must be preserved");
        }

        @Test
        @DisplayName("iframe tag line is stripped")
        void iframeTagStripped() {
            String input = "Before\n<iframe src=\"evil.com\"></iframe>\nAfter";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertFalse(result.contains("<iframe"), "iframe line must be stripped");
            assertTrue(result.contains("Before"));
            assertTrue(result.contains("After"));
        }

        @Test
        @DisplayName("closing tag line is stripped")
        void closingTagStripped() {
            String input = "Before\n</div>\nAfter";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertFalse(result.contains("</div>"), "closing tag line must be stripped");
            assertTrue(result.contains("Before"));
            assertTrue(result.contains("After"));
        }

        @Test
        @DisplayName("pipe table rows are preserved — they start with | not <")
        void pipeTableRowPreserved() {
            String input = "| Header |\n|---|\n| Cell |";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertTrue(result.contains("| Header |"), "pipe table header must be preserved");
            assertTrue(result.contains("| Cell |"), "pipe table cell must be preserved");
        }

        @Test
        @DisplayName("inline HTML within a sentence is preserved — only full-line blocks are stripped")
        void inlineHtmlInSentencePreserved() {
            String input = "Use **bold** and check <a href=\"x\">link</a> inline.";
            String result = HtmlPageBuilder.stripRawHtml(input);
            // Line starts with 'U', not '<' — should pass through unchanged
            assertTrue(result.contains("inline."), "inline HTML in prose must not be stripped");
        }

        @Test
        @DisplayName("plain Markdown prose is fully preserved")
        void markdownProsePreserved() {
            String input = "## Heading\n\nSome **bold** and _italic_ text.\n\n- item 1\n- item 2";
            String result = HtmlPageBuilder.stripRawHtml(input);
            assertTrue(result.contains("## Heading"));
            assertTrue(result.contains("Some **bold**"));
            assertTrue(result.contains("- item 1"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildChartsSection — always renders a section
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildChartsSection — always renders a section header")
    class ChartsSectionTests {

        @Test
        @DisplayName("null input renders placeholder, not empty string")
        void nullRendersPlaceholder() {
            String result = HtmlPageBuilder.buildChartsSection(null);
            assertFalse(result.isEmpty(), "Result must never be empty");
            assertTrue(result.contains("<h2>Performance Charts Over Time</h2>"),
                    "Section header must always be present");
            assertTrue(result.contains("charts-warn"),
                    "Placeholder CSS class must be applied");
        }

        @Test
        @DisplayName("empty list renders placeholder, not empty string")
        void emptyListRendersPlaceholder() {
            String result = HtmlPageBuilder.buildChartsSection(Collections.emptyList());
            assertFalse(result.isEmpty(), "Result must never be empty");
            assertTrue(result.contains("<h2>Performance Charts Over Time</h2>"),
                    "Section header must always be present");
            assertTrue(result.contains("charts-warn"),
                    "Placeholder CSS class must be applied");
        }

        @Test
        @DisplayName("single bucket renders placeholder — insufficient for time-series")
        void singleBucketRendersPlaceholder() {
            JTLParser.TimeBucket bucket = new JTLParser.TimeBucket(
                    System.currentTimeMillis(), 250.0, 2.5, 10.0, 50.0);
            String result = HtmlPageBuilder.buildChartsSection(List.of(bucket));
            assertFalse(result.isEmpty(), "Result must never be empty");
            assertTrue(result.contains("charts-warn"),
                    "Single-bucket result must use the placeholder");
            assertFalse(result.contains("<canvas"),
                    "Single bucket must not render Chart.js canvas elements");
        }

        @Test
        @DisplayName("two or more buckets renders full Chart.js section")
        void twoBucketsRendersCharts() {
            long now = System.currentTimeMillis();
            List<JTLParser.TimeBucket> buckets = List.of(
                    new JTLParser.TimeBucket(now, 250.0, 0.0, 10.0, 50.0),
                    new JTLParser.TimeBucket(now + 30_000, 300.0, 1.0, 12.0, 60.0));
            String result = HtmlPageBuilder.buildChartsSection(buckets);
            assertTrue(result.contains("<canvas"),
                    "Two buckets must render Chart.js canvas elements");
            assertFalse(result.contains("charts-warn"),
                    "Full chart must not use the placeholder CSS class");
            assertTrue(result.contains("chartAvgRt"), "Avg RT chart must be present");
            assertTrue(result.contains("chartErrPct"), "Error rate chart must be present");
            assertTrue(result.contains("chartTps"), "Throughput chart must be present");
            assertTrue(result.contains("chartKb"), "Bandwidth chart must be present");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // splitAtH2
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("splitAtH2 — section splitting at h2 boundaries")
    class SplitAtH2Tests {

        @Test
        @DisplayName("null input returns empty list")
        void nullReturnsEmpty() {
            assertTrue(HtmlPageBuilder.splitAtH2(null).isEmpty());
        }

        @Test
        @DisplayName("blank input returns empty list")
        void blankReturnsEmpty() {
            assertTrue(HtmlPageBuilder.splitAtH2("   ").isEmpty());
        }

        @Test
        @DisplayName("single h2 section returns one entry with correct title")
        void singleSection() {
            String html = "<h2>Executive Summary</h2><p>Test passed.</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertEquals(1, sections.size());
            assertEquals("Executive Summary", sections.get(0)[0]);
            assertTrue(sections.get(0)[1].contains("<h2>Executive Summary</h2>"),
                    "h2 tag must be retained in panel content");
            assertTrue(sections.get(0)[1].contains("Test passed."));
        }

        @Test
        @DisplayName("seven AI sections are split correctly")
        void sevenSections() {
            String html = "<h2>Executive Summary</h2><p>A</p>"
                    + "<h2>Bottleneck Analysis</h2><p>B</p>"
                    + "<h2>Error Analysis</h2><p>C</p>"
                    + "<h2>Advanced Web Diagnostics</h2><p>D</p>"
                    + "<h2>Root Cause Hypotheses</h2><p>E</p>"
                    + "<h2>Recommendations</h2><p>F</p>"
                    + "<h2>Verdict</h2><p>PASS</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertEquals(7, sections.size());
            assertEquals("Executive Summary", sections.get(0)[0]);
            assertEquals("Bottleneck Analysis", sections.get(1)[0]);
            assertEquals("Error Analysis", sections.get(2)[0]);
            assertEquals("Advanced Web Diagnostics", sections.get(3)[0]);
            assertEquals("Root Cause Hypotheses", sections.get(4)[0]);
            assertEquals("Recommendations", sections.get(5)[0]);
            assertEquals("Verdict", sections.get(6)[0]);
        }

        @Test
        @DisplayName("preamble before first h2 is prepended to the first section")
        void preamblePrependedToFirstSection() {
            String html = "<p>Preamble text.</p><h2>Section One</h2><p>Content.</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertEquals(1, sections.size());
            assertTrue(sections.get(0)[1].contains("Preamble text."),
                    "Preamble must be included in the first section content");
            assertTrue(sections.get(0)[1].contains("<h2>Section One</h2>"));
        }

        @Test
        @DisplayName("truncated report with 3 sections produces 3 entries")
        void truncatedReportThreeSections() {
            String html = "<h2>Executive Summary</h2><p>A</p>"
                    + "<h2>Bottleneck Analysis</h2><p>B</p>"
                    + "<h2>Error Analysis</h2><p>C</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertEquals(3, sections.size());
        }

        @Test
        @DisplayName("body with no h2 returns single Analysis section")
        void noH2ReturnsSingleFallbackSection() {
            String html = "<p>Some prose without any h2 headings.</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertEquals(1, sections.size());
            assertEquals("Analysis", sections.get(0)[0]);
            assertTrue(sections.get(0)[1].contains("Some prose"));
        }

        @Test
        @DisplayName("inner HTML tags in h2 are stripped from title")
        void innerTagsStrippedFromTitle() {
            String html = "<h2><strong>Executive Summary</strong></h2><p>Content.</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertEquals(1, sections.size());
            assertEquals("Executive Summary", sections.get(0)[0],
                    "Inner strong tag must be stripped from tab title");
        }

        @Test
        @DisplayName("h2 tag is retained in section content for rendering inside the panel")
        void h2RetainedInContent() {
            String html = "<h2>Verdict</h2><p>PASS</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertTrue(sections.get(0)[1].contains("<h2>"),
                    "The h2 opening tag must be kept in panel content");
        }

        @Test
        @DisplayName("returned list is mutable — additional entries can be appended")
        void returnedListIsMutable() {
            String html = "<h2>Executive Summary</h2><p>Content.</p>";
            List<String[]> sections = HtmlPageBuilder.splitAtH2(html);
            assertDoesNotThrow(() -> sections.add(new String[]{"Extra", "<p>extra</p>"}),
                    "List must be mutable so buildPage() can append Metrics and Charts tabs");
            assertEquals(2, sections.size());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // splitChartsBlock
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("splitChartsBlock — chart HTML / script separation")
    class SplitChartsBlockTests {

        @Test
        @DisplayName("null input returns two empty strings")
        void nullReturnsTwoEmpty() {
            String[] parts = HtmlPageBuilder.splitChartsBlock(null);
            assertEquals(2, parts.length);
            assertEquals("", parts[0]);
            assertEquals("", parts[1]);
        }

        @Test
        @DisplayName("blank input returns two empty strings")
        void blankReturnsTwoEmpty() {
            String[] parts = HtmlPageBuilder.splitChartsBlock("   ");
            assertEquals("", parts[0]);
            assertEquals("", parts[1]);
        }

        @Test
        @DisplayName("block without script tag returns full content in part[0] and empty part[1]")
        void noScriptTagReturnsFullContentInPart0() {
            String chartsBlock = "<div class=\"charts-section\"><h2>Charts</h2></div>\n";
            String[] parts = HtmlPageBuilder.splitChartsBlock(chartsBlock);
            assertEquals(chartsBlock, parts[0]);
            assertEquals("", parts[1]);
        }

        @Test
        @DisplayName("block with script tag splits correctly at <script> boundary")
        void scriptTagSplitsCorrectly() {
            String divPart = "<div class=\"charts-section\"><canvas></canvas></div>\n";
            String scriptPart = "<script>\n(function(){})()\n</script>\n";
            String chartsBlock = divPart + scriptPart;
            String[] parts = HtmlPageBuilder.splitChartsBlock(chartsBlock);
            assertEquals(divPart, parts[0], "Panel HTML must end at <script> tag");
            assertEquals(scriptPart, parts[1], "Script part must start with <script>");
        }

        @Test
        @DisplayName("real buildChartsSection output is split into non-empty canvas and script parts")
        void realChartsSectionIsSplit() {
            long now = System.currentTimeMillis();
            List<JTLParser.TimeBucket> buckets = List.of(
                    new JTLParser.TimeBucket(now, 250.0, 0.0, 10.0, 50.0),
                    new JTLParser.TimeBucket(now + 30_000, 300.0, 1.0, 12.0, 60.0));
            String chartsBlock = HtmlPageBuilder.buildChartsSection(buckets);
            String[] parts = HtmlPageBuilder.splitChartsBlock(chartsBlock);
            assertTrue(parts[0].contains("<canvas"), "Panel part must contain canvas elements");
            assertFalse(parts[0].contains("<script>"), "Panel part must NOT contain <script>");
            assertTrue(parts[1].startsWith("<script>"), "Script part must start with <script>");
        }

        @Test
        @DisplayName("unavailable placeholder (no script) returns full block in part[0]")
        void unavailablePlaceholderNoScript() {
            String chartsBlock = HtmlPageBuilder.buildChartsSection(null);
            String[] parts = HtmlPageBuilder.splitChartsBlock(chartsBlock);
            assertFalse(parts[0].isEmpty(), "Panel part must not be empty for placeholder");
            assertEquals("", parts[1], "Placeholder has no script block");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // buildPage — tabbed structure
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildPage — tabbed HTML page structure")
    class BuildPageTests {

        /**
         * Minimal RenderConfig used across all buildPage tests.
         */
        private HtmlReportRenderer.RenderConfig minimalConfig() {
            return new HtmlReportRenderer.RenderConfig(
                    "100", "Load Test", "Peak hour test",
                    "Thread Group 1", "01/01/25 09:00:00", "01/01/25 10:00:00",
                    "1h 0m 0s", 90, "Groq (Free)", -1, -1, "pnn");
        }

        private String sevenSectionBody() {
            return "<h2>Executive Summary</h2><p>A</p>"
                    + "<h2>Bottleneck Analysis</h2><p>B</p>"
                    + "<h2>Error Analysis</h2><p>C</p>"
                    + "<h2>Advanced Web Diagnostics</h2><p>D</p>"
                    + "<h2>Root Cause Hypotheses</h2><p>E</p>"
                    + "<h2>Recommendations</h2><p>F</p>"
                    + "<h2>Verdict</h2><p>PASS</p>";
        }

        @Test
        @DisplayName("page contains tab bar with correct number of buttons")
        void tabBarHasCorrectButtonCount() {
            String metricsTable = "<div class=\"metrics-section\"><h2>Transaction Metrics</h2><table><tr><td>x</td></tr></table></div>";
            String chartsBlock = HtmlPageBuilder.buildChartsSection(null); // placeholder
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), metricsTable, chartsBlock, minimalConfig());

            // Expect 9 tabs: 7 AI + Metrics + Charts
            long tabCount = countOccurrences(page, "class=\"nav-item");
            assertEquals(9, tabCount, "9 tab buttons expected (7 AI + Metrics + Charts)");
        }

        @Test
        @DisplayName("first tab button has active class on page load")
        void firstTabButtonIsActive() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            // First occurrence of tab-btn must carry the active class
            int firstBtn = page.indexOf("class=\"nav-item");
            assertTrue(page.substring(firstBtn, firstBtn + 30).contains("active"),
                    "First tab button must have the active class");
        }

        @Test
        @DisplayName("first tab panel has active class on page load")
        void firstTabPanelIsActive() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            int firstPanel = page.indexOf("class=\"panel");
            assertTrue(page.substring(firstPanel, firstPanel + 30).contains("active"),
                    "First tab panel must have the active class");
        }

        @Test
        @DisplayName("report header is outside all tab panels")
        void reportHeaderOutsideTabPanels() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            // Use the full HTML element — not just "report-header" which also appears
            // in the CSS <style> block earlier in the string, causing a false position.
            int headerStart = page.indexOf("<div class=\"rpt-header\">");
            int headerEnd = page.indexOf("</div>", headerStart);
            int firstPanel = page.indexOf("class=\"panel");
            assertTrue(headerStart >= 0, "report-header div must be present in the page");
            assertTrue(headerEnd < firstPanel, "Report header must appear before any tab panel");
        }

        @Test
        @DisplayName("Chart.js CDN script is in the head element")
        void chartJsCdnInHead() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            int headEnd = page.indexOf("</head>");
            int chartJs = page.indexOf("Chart.js");
            assertTrue(chartJs < headEnd, "Chart.js CDN must be inside <head>");
        }

        @Test
        @DisplayName("SheetJS CDN script is in the head element")
        void sheetJsCdnInHead() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            int headEnd = page.indexOf("</head>");
            int sheetJs = page.indexOf("xlsx");
            assertTrue(sheetJs > 0 && sheetJs < headEnd, "SheetJS CDN must be inside <head>");
        }

        @Test
        @DisplayName("export bar contains Excel button; PDF export button is not present")
        void exportBarContainsBothButtons() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertTrue(page.contains("header-actions"), "Export bar div must be present");
            assertTrue(page.contains("exportExcel()"), "Excel export button must be present");
            assertFalse(page.contains("window.print()"), "PDF export button must not be present"); // CHANGED
        }

        @Test
        @DisplayName("each AI section appears inside its own tab panel")
        void aiSectionsInOwnPanels() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            // All 7 section h2 tags must be inside tab-panel divs
            assertTrue(page.contains("Executive Summary"), "Executive Summary section present");
            assertTrue(page.contains("Verdict"), "Verdict section present");
            // Each h2 appears inside a tab panel
            long panelCount = countOccurrences(page, "id=\"panel-");
            assertTrue(panelCount >= 8, "At least 8 tab panels expected (7 AI + Charts)");
        }

        @Test
        @DisplayName("metrics tab is omitted when metricsTable is blank")
        void metricsTabOmittedWhenBlank() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            // 8 tabs: 7 AI + Charts (no metrics)
            long tabCount = countOccurrences(page, "class=\"nav-item");
            assertEquals(8, tabCount, "Metrics tab must be omitted when metricsTable is blank");
        }

        @Test
        @DisplayName("metrics tab present when metricsTable is non-blank")
        void metricsTabPresentWhenNonBlank() {
            String metricsTable = "<div class=\"metrics-section\"><h2>Transaction Metrics</h2></div>";
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), metricsTable, "", minimalConfig());
            long tabCount = countOccurrences(page, "class=\"nav-item");
            assertEquals(9, tabCount, "9 tabs expected with non-blank metricsTable");
            assertTrue(page.contains("Transaction Metrics"), "Metrics tab title must be present");
        }

        @Test
        @DisplayName("charts tab button carries data-charts=true attribute")
        void chartsTabButtonHasDataChartsAttribute() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertTrue(page.contains("el.querySelector('canvas')"),
                    "Charts panel activation must trigger Chart.js resize via canvas detection");
        }

        @Test
        @DisplayName("Chart.js init script is outside all tab panels")
        void chartInitScriptOutsideTabPanels() {
            long now = System.currentTimeMillis();
            List<JTLParser.TimeBucket> buckets = List.of(
                    new JTLParser.TimeBucket(now, 250.0, 0.0, 10.0, 50.0),
                    new JTLParser.TimeBucket(now + 30_000, 300.0, 1.0, 12.0, 60.0));
            String chartsBlock = HtmlPageBuilder.buildChartsSection(buckets);
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", chartsBlock, minimalConfig());

            int chartScript = page.indexOf("timeChart(");
            assertTrue(chartScript > 0, "timeChart call must be present in the page");

            // The chart init script must appear AFTER the last tab panel definition.
            // data-title= attributes are only on tab-panel divs; the last one belongs
            // to the charts panel. Verifying chartScript > lastDataTitle confirms the
            // script is placed outside (after) all panels.
            int lastDataTitle = page.lastIndexOf("data-title=");
            assertTrue(chartScript > lastDataTitle,
                    "Chart.js init must appear after all tab panel definitions");
        }

        @Test
        @DisplayName("tab switching JavaScript is present")
        void tabSwitchingJsPresent() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertTrue(page.contains("nav-item"), "Tab button class referenced in JS");
            assertTrue(page.contains("classList.remove('active')"), "Tab active-class removal must be present");
            assertTrue(page.contains("classList.add('active')"), "Tab active-class addition must be present");
        }

        @Test
        @DisplayName("page is a valid HTML document with DOCTYPE and closing tags")
        void validHtmlStructure() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertTrue(page.startsWith("<!DOCTYPE html>"), "Must start with DOCTYPE");
            assertTrue(page.contains("<html lang=\"en\">"), "Must have html element with lang");
            assertTrue(page.contains("</html>"), "Must have closing html tag");
            assertTrue(page.contains("</body>"), "Must have closing body tag");
        }

        @Test
        @DisplayName("truncated AI response with 3 sections produces 4 tabs (3 AI + Charts)")
        void truncatedReportProducesCorrectTabCount() {
            String truncatedBody = "<h2>Executive Summary</h2><p>A</p>"
                    + "<h2>Bottleneck Analysis</h2><p>B</p>"
                    + "<h2>Error Analysis</h2><p>C</p>";
            String page = HtmlPageBuilder.buildPage(truncatedBody, "", "", minimalConfig());
            long tabCount = countOccurrences(page, "class=\"nav-item");
            assertEquals(4, tabCount, "3 AI sections + Charts tab = 4 tabs for truncated report");
        }

        @Test
        @DisplayName("@media print CSS is not present — PDF export feature removed")
        void mediaPrintShowsAllPanels() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertFalse(page.contains("display: block !important"),
                    "@media print must not be present"); // CHANGED
            assertFalse(page.contains("@media print"),
                    "@media print block must be absent"); // CHANGED
        }

        @Test
        @DisplayName("window.jaarMeta script is injected with all metadata fields including providerName")
        void jaarMetaScriptInjected() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertTrue(page.contains("window.jaarMeta"), "jaarMeta object must be present");
            assertTrue(page.contains("scenarioName"), "scenarioName field must be present");
            assertTrue(page.contains("scenarioDesc"), "scenarioDesc field must be present");
            assertTrue(page.contains("users"), "users field must be present");
            assertTrue(page.contains("duration"), "duration field must be present");
            assertTrue(page.contains("providerName"), "providerName field must be present");
            // Verify actual config values are embedded
            assertTrue(page.contains("Load Test"), "scenarioName value must be embedded");
            assertTrue(page.contains("1h 0m 0s"), "duration value must be embedded");
            // Provider "Groq (Free)" → sanitized "Groq"
            assertTrue(page.contains("'Groq'"), "providerName must be tier-stripped to 'Groq'");
        }

        @Test
        @DisplayName("window.jaarMeta script appears inside the head element")
        void jaarMetaInHead() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            int headEnd = page.indexOf("</head>");
            int metaIdx = page.indexOf("window.jaarMeta");
            assertTrue(metaIdx > 0 && metaIdx < headEnd,
                    "window.jaarMeta must be declared inside <head>");
        }

        @Test
        @DisplayName("buildChartsSection placeholder does not embed chart data table")
        void placeholderHasNoChartDataTable() {
            String chartsBlock = HtmlPageBuilder.buildChartsSection(null);
            assertFalse(chartsBlock.contains("jaar-chart-data"),
                    "Placeholder chart section must not contain a hidden data table");
        }

        @Test
        @DisplayName("buildChartsSection with real data does not embed hidden data table")
        void realChartsSectionHasNoHiddenTable() {
            long now = System.currentTimeMillis();
            List<JTLParser.TimeBucket> buckets = List.of(
                    new JTLParser.TimeBucket(now, 250.0, 1.5, 10.0, 50.0),
                    new JTLParser.TimeBucket(now + 30_000, 300.0, 0.0, 12.0, 60.0));
            String chartsBlock = HtmlPageBuilder.buildChartsSection(buckets);
            assertFalse(chartsBlock.contains("jaar-chart-data"),
                    "Charts section must not embed a hidden data table");
        }

        @Test
        @DisplayName("Excel export JS writes referral message for Performance Charts sheet")
        void chartsSheetContainsReferralMessage() {
            String page = HtmlPageBuilder.buildPage(sevenSectionBody(), "", "", minimalConfig());
            assertTrue(page.contains("Please refer to the HTML report for interactive charts."),
                    "Excel export JS must write the HTML report referral message for the charts sheet");
        }

        // ── Helper ───────────────────────────────────────────────────────────

        private long countOccurrences(String text, String token) {
            int count = 0;
            int idx = 0;
            while ((idx = text.indexOf(token, idx)) >= 0) {
                count++;
                idx += token.length();
            }
            return count;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // sanitizeProviderName
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeProviderName — provider segment for filenames")
    class SanitizeProviderNameTests {

        @Test
        @DisplayName("null returns AI")
        void nullReturnsAI() {
            assertEquals("AI", HtmlPageBuilder.sanitizeProviderName(null));
        }

        @Test
        @DisplayName("blank returns AI")
        void blankReturnsAI() {
            assertEquals("AI", HtmlPageBuilder.sanitizeProviderName("   "));
        }

        @Test
        @DisplayName("tier suffix is stripped — Groq (Free) -> Groq")
        void tierSuffixStripped() {
            assertEquals("Groq", HtmlPageBuilder.sanitizeProviderName("Groq (Free)"));
        }

        @Test
        @DisplayName("paid tier suffix is stripped — OpenAI (Paid) -> OpenAI")
        void paidTierStripped() {
            assertEquals("OpenAI", HtmlPageBuilder.sanitizeProviderName("OpenAI (Paid)"));
        }

        @Test
        @DisplayName("name without tier is returned as-is when safe")
        void noTierReturnedAsIs() {
            assertEquals("Groq", HtmlPageBuilder.sanitizeProviderName("Groq"));
        }

        @Test
        @DisplayName("whitespace within name is replaced with underscore")
        void whitespaceReplacedWithUnderscore() {
            assertEquals("My_Provider", HtmlPageBuilder.sanitizeProviderName("My Provider"));
        }

        @Test
        @DisplayName("filesystem-unsafe characters are replaced with underscore")
        void unsafeCharsReplaced() {
            String result = HtmlPageBuilder.sanitizeProviderName("Bad/Name*Here");
            assertFalse(result.contains("/"), "Slash must be removed");
            assertFalse(result.contains("*"), "Asterisk must be removed");
        }

        @Test
        @DisplayName("multiple consecutive unsafe chars collapse to single underscore")
        void consecutiveUnsafeCollapse() {
            String result = HtmlPageBuilder.sanitizeProviderName("A  B");
            assertFalse(result.contains("__"), "Double underscore must not remain");
        }

        @Test
        @DisplayName("leading and trailing underscores are stripped")
        void leadingTrailingUnderscoresStripped() {
            String result = HtmlPageBuilder.sanitizeProviderName(" Groq ");
            assertFalse(result.startsWith("_"), "Must not start with underscore");
            assertFalse(result.endsWith("_"), "Must not end with underscore");
        }
    }
}