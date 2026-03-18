package com.personal.jmeter.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link MarkdownUtils}.
 *
 * <p>Covers every branch of {@link MarkdownUtils#extractVerdict(String)} and
 * {@link MarkdownUtils#stripVerdictLine(String)}, including the truncation-resilience
 * protocol that uses {@code BRIEF_VERDICT:} as an early anchor token alongside
 * the canonical {@code VERDICT:} token at the end of the document.</p>
 */
@DisplayName("MarkdownUtils")
class MarkdownUtilsTest {

    // ─────────────────────────────────────────────────────────────
    // extractVerdict
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractVerdict")
    class ExtractVerdict {

        // ── Canonical path: VERDICT: at end ───────────────────────

        @Test
        @DisplayName("VERDICT:PASS on last non-blank line returns PASS")
        void lastLine_verdictPass() {
            String md = "## Verdict\nAll SLAs met.\nVERDICT:PASS";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("bold **VERDICT:PASS** is recognised — Mistral formatting")
        void boldVerdictPass() {
            String md = "## Verdict\nAll SLAs met.\n**VERDICT:PASS**";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("bold **VERDICT:FAIL** is recognised")
        void boldVerdictFail() {
            String md = "## Verdict\nSLA breached.\n**VERDICT:FAIL**";
            assertEquals("FAIL", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("italic *VERDICT:PASS* is recognised")
        void italicVerdictPass() {
            assertEquals("PASS", MarkdownUtils.extractVerdict("Some text.\n*VERDICT:PASS*"));
        }

        @Test
        @DisplayName("VERDICT:PASS inline at end of sentence — Cerebras qwen pattern")
        void inlineVerdictPass() {
            // Cerebras writes token inline: "...within the 10% threshold. VERDICT:PASS"
            String md = "## Verdict\nPASS — Error Rate SLA within the 10% threshold. VERDICT:PASS";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("VERDICT:FAIL inline at end of sentence")
        void inlineVerdictFail() {
            String md = "## Verdict\nFAIL — Error rate 3.88% breaches 2% threshold. VERDICT:FAIL";
            assertEquals("FAIL", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("BRIEF_VERDICT:PASS inline at end of sentence")
        void inlineBriefVerdictPass() {
            String md = "## Executive Summary\nThe test passed all SLAs. BRIEF_VERDICT:PASS\n## Bottleneck";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("VERDICT:FAIL on last non-blank line returns FAIL")
        void lastLine_verdictFail() {
            String md = "## Verdict\nError rate exceeded SLA.\nVERDICT:FAIL";
            assertEquals("FAIL", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("trailing blank lines after VERDICT token are ignored")
        void trailingBlankLines_afterVerdict() {
            String md = "Some analysis.\nVERDICT:PASS\n\n  \n";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("VERDICT:UNKNOWN (unrecognised token) falls through to BRIEF_VERDICT scan")
        void unknownVerdictToken_fallsThroughToBriefScan() {
            // VERDICT:UNKNOWN is not recognised → Pass 1 exits → Pass 2 finds BRIEF_VERDICT
            String md = "BRIEF_VERDICT:PASS\n## Verdict\nVERDICT:UNKNOWN";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("VERDICT token not on last non-blank line — falls back to BRIEF_VERDICT")
        void verdictNotOnLastLine_fallsBackToBriefVerdict() {
            // VERDICT:PASS mid-document, last non-blank is regular text → not a VERDICT: token
            // → Pass 1 exits with break → Pass 2 finds BRIEF_VERDICT:PASS
            String md = "BRIEF_VERDICT:PASS\nVERDICT:PASS\nThis is extra text after the verdict.";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        // ── Resilient fallback path: BRIEF_VERDICT: anywhere ─────

        @Test
        @DisplayName("BRIEF_VERDICT:PASS found when VERDICT: absent — truncated response")
        void briefVerdictPass_whenVerdictAbsent() {
            // Simulates truncation before Verdict section
            String md = "## Executive Summary\nTest ran.\nBRIEF_VERDICT:PASS\n## Bottleneck Analysis\nSome text cut off";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("BRIEF_VERDICT:FAIL found when VERDICT: absent — truncated response")
        void briefVerdictFail_whenVerdictAbsent() {
            String md = "## Executive Summary\nError rate breached.\nBRIEF_VERDICT:FAIL\n## Bottleneck";
            assertEquals("FAIL", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("canonical VERDICT: takes precedence over BRIEF_VERDICT: in complete response")
        void canonicalVerdictTakesPrecedence() {
            // Both present — canonical VERDICT: at end wins via Pass 1
            String md = "BRIEF_VERDICT:PASS\n## Verdict\nSLA met.\nVERDICT:PASS";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("BRIEF_VERDICT contradicts VERDICT — canonical VERDICT wins")
        void briefVerdictContradicts_canonicalWins() {
            // Pathological case — canonical at end wins
            String md = "BRIEF_VERDICT:FAIL\n## Verdict\nAll SLAs met.\nVERDICT:PASS";
            assertEquals("PASS", MarkdownUtils.extractVerdict(md));
        }

        // ── Neither token present ──────────────────────────────────

        @Test
        @DisplayName("no VERDICT or BRIEF_VERDICT token — returns UNDECISIVE")
        void noVerdictToken_returnsUndecisive() {
            String md = "## Root Cause Hypotheses\nServer saturated — infrastructure/";
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict(md));
        }

        @Test
        @DisplayName("null input returns UNDECISIVE")
        void nullInput_returnsUndecisive() {
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict(null));
        }

        @Test
        @DisplayName("blank input returns UNDECISIVE")
        void blankInput_returnsUndecisive() {
            assertEquals("UNDECISIVE", MarkdownUtils.extractVerdict("   \n  \n"));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // verdictSource
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("verdictSource")
    class VerdictSource {

        @Test
        @DisplayName("VERDICT: at last non-blank line returns CANONICAL")
        void canonical_verdictAtEnd() {
            assertEquals("CANONICAL",
                    MarkdownUtils.verdictSource("## Verdict\nAll SLAs met.\nVERDICT:PASS"));
        }

        @Test
        @DisplayName("VERDICT:FAIL at last non-blank line returns CANONICAL")
        void canonical_verdictFailAtEnd() {
            assertEquals("CANONICAL",
                    MarkdownUtils.verdictSource("## Verdict\nSLA breached.\nVERDICT:FAIL"));
        }

        @Test
        @DisplayName("BRIEF_VERDICT present, VERDICT absent returns FALLBACK")
        void fallback_briefVerdictOnly() {
            String md = "## Executive Summary\nTest ran.\nBRIEF_VERDICT:PASS\n## Bottleneck cut off";
            assertEquals("FALLBACK", MarkdownUtils.verdictSource(md));
        }

        @Test
        @DisplayName("both tokens present — CANONICAL wins")
        void canonical_takesPrecedenceOverBrief() {
            String md = "BRIEF_VERDICT:PASS\n## Verdict\nAll SLAs met.\nVERDICT:PASS";
            assertEquals("CANONICAL", MarkdownUtils.verdictSource(md));
        }

        @Test
        @DisplayName("neither token present returns UNDECISIVE")
        void undecisive_noTokens() {
            assertEquals("UNDECISIVE",
                    MarkdownUtils.verdictSource("## Root Cause\nSome analysis cut off"));
        }

        @Test
        @DisplayName("null input returns UNDECISIVE")
        void undecisive_nullInput() {
            assertEquals("UNDECISIVE", MarkdownUtils.verdictSource(null));
        }

        @Test
        @DisplayName("blank input returns UNDECISIVE")
        void undecisive_blankInput() {
            assertEquals("UNDECISIVE", MarkdownUtils.verdictSource("  \n  "));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // stripVerdictLine
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stripVerdictLine")
    class StripVerdictLine {

        @Test
        @DisplayName("VERDICT:PASS line is removed")
        void stripVerdictPass() {
            String md = "## Verdict\nAll metrics within SLA.\nVERDICT:PASS";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT:PASS"), "VERDICT line must be removed");
            assertTrue(result.contains("All metrics within SLA."), "preceding content preserved");
        }

        @Test
        @DisplayName("bold **VERDICT:FAIL** line is stripped — Mistral formatting")
        void stripBoldVerdictFail() {
            String md = "## Verdict\nSLA breached.\n**VERDICT:FAIL**";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT:FAIL"), "bold VERDICT line must be removed");
            assertTrue(result.contains("SLA breached."), "preceding content preserved");
        }

        @Test
        @DisplayName("italic *BRIEF_VERDICT:PASS* is stripped")
        void stripItalicBriefVerdict() {
            String md = "## Exec\n*BRIEF_VERDICT:PASS*\n## Bottleneck";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("BRIEF_VERDICT:"), "italic BRIEF_VERDICT must be removed");
            assertTrue(result.contains("## Bottleneck"), "later content preserved");
        }

        @Test
        @DisplayName("inline VERDICT:PASS at end of sentence — token stripped, prose preserved")
        void stripInlineVerdictPass() {
            // Cerebras qwen pattern: token appended to verdict sentence
            String md = "## Verdict\nPASS — SLA within the 10% threshold. VERDICT:PASS";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT:PASS"), "inline token must be removed");
            assertTrue(result.contains("PASS — SLA within the 10% threshold"),
                    "verdict prose must be preserved");
        }

        @Test
        @DisplayName("VERDICT:FAIL line is removed")
        void stripVerdictFail() {
            String md = "## Verdict\nError rate breached SLA.\nVERDICT:FAIL";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT:FAIL"), "VERDICT line must be removed");
            assertTrue(result.contains("Error rate breached SLA."), "preceding content preserved");
        }

        @Test
        @DisplayName("BRIEF_VERDICT line is removed from mid-document")
        void stripBriefVerdict() {
            String md = "## Executive Summary\nTest ran.\nBRIEF_VERDICT:PASS\n## Bottleneck Analysis";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("BRIEF_VERDICT:"), "BRIEF_VERDICT line must be removed");
            assertTrue(result.contains("## Executive Summary"), "earlier content preserved");
            assertTrue(result.contains("## Bottleneck Analysis"), "later content preserved");
        }

        @Test
        @DisplayName("both BRIEF_VERDICT and VERDICT lines are removed in one pass")
        void stripBothTokens() {
            String md = "## Exec\nBRIEF_VERDICT:FAIL\n## Verdict\nSLA breached.\nVERDICT:FAIL";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("BRIEF_VERDICT:"), "BRIEF_VERDICT removed");
            assertFalse(result.contains("VERDICT:"),       "VERDICT removed");
            assertTrue(result.contains("SLA breached."),   "prose preserved");
        }

        @Test
        @DisplayName("trailing blank lines stripped; preceding content preserved")
        void trailingBlankLinesStripped() {
            String md = "Some content.\nVERDICT:FAIL\n\n\n";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertFalse(result.contains("VERDICT"), "VERDICT line must be removed");
            assertTrue(result.endsWith("Some content."), "no trailing whitespace after strip");
        }

        @Test
        @DisplayName("no verdict lines present returns original markdown unchanged")
        void noVerdictLine_returnsOriginal() {
            String md = "## Root Cause Hypotheses\nServer saturated — infrastructure/";
            assertEquals(md, MarkdownUtils.stripVerdictLine(md));
        }

        @Test
        @DisplayName("null input returns null without throwing")
        void nullInput_returnsNull() {
            assertNull(MarkdownUtils.stripVerdictLine(null));
        }

        @Test
        @DisplayName("blank input returns blank without throwing")
        void blankInput_returnsBlank() {
            String blank = "  \n  ";
            assertEquals(blank, MarkdownUtils.stripVerdictLine(blank));
        }

        @Test
        @DisplayName("only verdict token lines removed; all other content intact")
        void onlyVerdictLinesRemoved() {
            String body = "## Executive Summary\nTest ran.\n\n## Verdict\nFAIL by error rate.";
            String md   = "BRIEF_VERDICT:FAIL\n" + body + "\nVERDICT:FAIL";
            String result = MarkdownUtils.stripVerdictLine(md);
            assertTrue(result.contains("## Executive Summary"), "earlier content preserved");
            assertTrue(result.contains("## Verdict"),           "Verdict section heading preserved");
            assertTrue(result.contains("FAIL by error rate."),  "verdict prose preserved");
            assertFalse(result.contains("VERDICT:FAIL"),        "machine token removed");
            assertFalse(result.contains("BRIEF_VERDICT:"),      "brief token removed");
        }
    }
}