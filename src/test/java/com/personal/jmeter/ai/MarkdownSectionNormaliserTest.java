package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarkdownSectionNormaliser}.
 *
 * <p>The normaliser removes duplicate section headings that arise when a model
 * repeats a heading from the assistant skeleton prefill. Tests cover the
 * duplicate removal, no-op cases, and content preservation.</p>
 */
@DisplayName("MarkdownSectionNormaliser")
class MarkdownSectionNormaliserTest {

    @Nested
    @DisplayName("no-op cases")
    class NoOp {

        @Test
        @DisplayName("null input returns null")
        void nullInput() {
            assertNull(MarkdownSectionNormaliser.normalise(null));
        }

        @Test
        @DisplayName("blank input returns blank unchanged")
        void blankInput() {
            String blank = "   \n  ";
            assertEquals(blank, MarkdownSectionNormaliser.normalise(blank));
        }

        @Test
        @DisplayName("no duplicate headings — returns same reference")
        void noDuplicates() {
            String md = "## Executive Summary\n\nContent.\n\n"
                    + "## Bottleneck Analysis\n\nBTA content.\n\n"
                    + "## Verdict\n\nPASS — SLAs met.\nVERDICT:PASS";
            assertSame(md, MarkdownSectionNormaliser.normalise(md),
                    "no change expected — same reference returned");
        }

        @Test
        @DisplayName("non-section headings (h3, h4) are not affected")
        void nonSectionHeadingsIgnored() {
            String md = "## Executive Summary\n\n### Sub-heading\n\n### Sub-heading\n\nContent.";
            String result = MarkdownSectionNormaliser.normalise(md);
            // ### Sub-heading is not in EXPECTED_HEADINGS — must not be stripped
            assertEquals(2, countOccurrences(result, "### Sub-heading"),
                    "non-section headings must not be deduplicated");
        }
    }

    @Nested
    @DisplayName("duplicate removal")
    class DuplicateRemoval {

        @Test
        @DisplayName("duplicate ## Executive Summary — keeps LAST (model's with content), drops first (skeleton's empty)")
        void duplicateExecSummaryRemoved() {
            // Mistral pattern: skeleton prefill adds empty heading first,
            // Mistral then writes it again with content after it.
            // normalise() must keep the LAST occurrence (has content) and drop the FIRST (empty).
            String md = "## Executive Summary\n\n"                     // skeleton — empty
                    + "## Executive Summary\n\n"                        // model's — content follows
                    + "The load test ran for 3 hours.\n\n"
                    + "## Bottleneck Analysis\n\nTB content.\n\n"
                    + "## Verdict\n\nPASS.\nVERDICT:PASS";
            String result = MarkdownSectionNormaliser.normalise(md);
            assertEquals(1, countOccurrences(result, "## Executive Summary"),
                    "only one heading must remain");
            assertTrue(result.contains("The load test ran for 3 hours."),
                    "model content must be preserved");
            // Content must sit directly under the heading
            int headingIdx = result.indexOf("## Executive Summary");
            int contentIdx = result.indexOf("The load test ran for 3 hours.");
            int nextH2Idx  = result.indexOf("## Bottleneck Analysis");
            assertTrue(contentIdx > headingIdx && contentIdx < nextH2Idx,
                    "content must appear between ## Executive Summary and ## Bottleneck Analysis");
        }

        @Test
        @DisplayName("duplicate mid-report heading — keeps last occurrence")
        void duplicateMidReportHeadingRemoved() {
            String md = "## Executive Summary\n\nExec.\n\n"
                    + "## Bottleneck Analysis\n\n"       // empty first occurrence
                    + "## Bottleneck Analysis\n\nTB content.\n\n"  // model's with content
                    + "## Verdict\n\nPASS.\nVERDICT:PASS";
            String result = MarkdownSectionNormaliser.normalise(md);
            assertEquals(1, countOccurrences(result, "## Bottleneck Analysis"));
            assertTrue(result.contains("TB content."), "content from last occurrence preserved");
        }

        @Test
        @DisplayName("content between headings is never modified")
        void contentBetweenHeadingsPreserved() {
            String md = "## Executive Summary\n\n"
                    + "## Executive Summary\n\n"
                    + "Exact content line 1.\nExact content line 2.\n\n"
                    + "## Bottleneck Analysis\n\nBTA.";
            String result = MarkdownSectionNormaliser.normalise(md);
            assertTrue(result.contains("Exact content line 1."), "line 1 preserved");
            assertTrue(result.contains("Exact content line 2."), "line 2 preserved");
        }

        @Test
        @DisplayName("only the first occurrence kept — third occurrence also removed")
        void onlyFirstOccurrenceKept() {
            String md = "## Verdict\n\n## Verdict\n\n## Verdict\n\nPASS.\nVERDICT:PASS";
            String result = MarkdownSectionNormaliser.normalise(md);
            assertEquals(1, countOccurrences(result, "## Verdict"),
                    "all duplicates removed — only first kept");
        }

        @Test
        @DisplayName("multiple different duplicate headings all removed")
        void multipleDifferentDuplicatesRemoved() {
            String md = "## Executive Summary\n\n## Executive Summary\n\nExec.\n\n"
                    + "## Bottleneck Analysis\n\n## Bottleneck Analysis\n\nBTA.\n\n"
                    + "## Verdict\n\nPASS.\nVERDICT:PASS";
            String result = MarkdownSectionNormaliser.normalise(md);
            assertEquals(1, countOccurrences(result, "## Executive Summary"));
            assertEquals(1, countOccurrences(result, "## Bottleneck Analysis"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────

    private static int countOccurrences(String text, String target) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(target, idx)) != -1) { count++; idx += target.length(); }
        return count;
    }
}