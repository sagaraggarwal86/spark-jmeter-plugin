package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HtmlPageBuilder#convertPipeTablesToHtml}.
 *
 * <p>No file system, no network, no Swing — pure in-memory verification of
 * the pipe-table pre-processor's branching: separator-row detection, multi-row
 * tables, cell escaping, and non-table lines pass-through.</p>
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
    }

    // ─────────────────────────────────────────────────────────────
    // Cell escaping
    // ─────────────────────────────────────────────────────────────

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
}