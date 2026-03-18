package com.personal.jmeter.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AiReportService#extractAndValidateContent(String)}.
 *
 * <p>No HTTP client is involved — the method is exercised directly
 * (package-private) using hand-crafted JSON response strings that simulate
 * the OpenAI-compatible /v1/messages schema.</p>
 *
 * <p>All content assertions account for {@link AiReportService#SECTION_SKELETON}
 * being prepended to the model's continuation before the method returns.</p>
 */
@DisplayName("AiReportService — extractAndValidateContent")
class AiReportServiceTest {

    /** Mirror of {@link AiReportService#SECTION_SKELETON} for assertion use. */
    private static final String SKELETON = AiReportService.SECTION_SKELETON;

    /**
     * A minimal but structurally complete 7-section markdown response.
     * Used in happy-path tests that must not trigger the missing-sections notice.
     */
    private static final String FULL_CONTENT =
            "## Executive Summary\n\nThe test ran well. BRIEF_VERDICT:PASS\n\n"
                    + "## Bottleneck Analysis\n\nTHROUGHPUT-BOUND.\n\n"
                    + "## Error Analysis\n\nError rate 1.01%.\n\n"
                    + "## Advanced Web Diagnostics\n\nConnect 0ms.\n\n"
                    + "## Root Cause Hypotheses\n\n#1 Backend processing.\n\n"
                    + "## Recommendations\n\n| Priority | Hypothesis | Action | Expected Impact | Effort |\n"
                    + "|---|---|---|---|---|\n| P2 | #1 | Optimise queries | Reduce latency | Medium |\n\n"
                    + "## Verdict\n\nPASS — all SLAs met.\nVERDICT:PASS";

    /** JSON-escaped version of {@link #FULL_CONTENT} for embedding in JSON strings. */
    private static final String FULL_CONTENT_ESCAPED = FULL_CONTENT
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n");

    private AiReportService service;

    @BeforeEach
    void setUp() {
        // Minimal config sufficient for the method under test.
        // Constructor order: providerKey, displayName, apiKey, model, baseUrl,
        //                    timeoutSeconds, maxTokens, temperature
        AiProviderConfig config = new AiProviderConfig(
                "gemini", "Gemini (Free)", "fake-key",
                "gemini-2.0-flash", "https://example.com/v1/chat/completions",
                120, 1500, 0.3);
        service = new AiReportService(config);
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Normal (non-truncated) responses")
    class NormalResponses {

        @Test
        @DisplayName("finish_reason=stop — response with heading, no skeleton prepend")
        void finishReasonStop_returnsContentUnchanged() throws AiServiceException {
            // FULL_CONTENT starts with ## Executive Summary → skeleton NOT prepended
            String json = buildResponse(FULL_CONTENT, "stop");
            String result = service.extractAndValidateContent(json);
            assertEquals(FULL_CONTENT, result);
        }

        @Test
        @DisplayName("finish_reason=end_turn — response with heading, no skeleton prepend")
        void finishReasonEndTurn_returnsContentUnchanged() throws AiServiceException {
            String json = buildResponse(FULL_CONTENT, "end_turn");
            String result = service.extractAndValidateContent(json);
            assertEquals(FULL_CONTENT, result);
        }

        @Test
        @DisplayName("missing finish_reason — response with heading, no skeleton prepend")
        void missingFinishReason_returnsContentUnchanged() throws AiServiceException {
            String json = "{"
                    + "\"choices\":[{"
                    + "\"message\":{\"content\":\"" + FULL_CONTENT_ESCAPED + "\"},"
                    + "\"index\":0"
                    + "}]}";
            String result = service.extractAndValidateContent(json);
            assertEquals(FULL_CONTENT, result);
        }

        @Test
        @DisplayName("response without heading — skeleton prepended (Cerebras pattern)")
        void noHeading_skeletonPrepended() throws AiServiceException {
            // Cerebras omits ## Executive Summary → skeleton must be prepended
            String noHeading = "The load test ran well.\n\n"
                    + "## Bottleneck Analysis\n\nTB.\n\n"
                    + "## Error Analysis\n\nErr.\n\n"
                    + "## Advanced Web Diagnostics\n\nDiag.\n\n"
                    + "## Root Cause Hypotheses\n\nHyp.\n\n"
                    + "## Recommendations\n\nRow.\n\n"
                    + "## Verdict\n\nPASS.\nVERDICT:PASS";
            String json = buildResponse(noHeading, "stop");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith(SKELETON),
                    "Skeleton must be prepended when heading is absent");
            assertEquals(1, countOccurrences(result, "## Executive Summary"),
                    "Exactly one ES heading in result");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Missing sections detection
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Missing sections detection")
    class MissingSectionsDetection {

        @Test
        @DisplayName("all 7 sections present — no missing sections notice")
        void allSectionsPresent_noNotice() throws AiServiceException {
            String json = buildResponse(FULL_CONTENT, "stop");
            String result = service.extractAndValidateContent(json);
            assertFalse(result.contains("Missing sections detected"),
                    "No missing sections notice when all 7 sections are present");
            assertFalse(result.contains("Partial report"),
                    "No partial report notice when all 7 sections are present");
        }

        @Test
        @DisplayName("missing sections without truncation — model silently skipped notice")
        void missingSections_notTruncated_noticeInjected() throws AiServiceException {
            String partial = "## Executive Summary\n\nThe test ran well. VERDICT:PASS";
            String json = buildResponse(partial, "stop");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("Missing sections detected"),
                    "Missing sections notice must be injected");
            assertTrue(result.contains("Bottleneck Analysis"),
                    "Notice must name the missing section");
            assertTrue(result.contains("Regenerate the report"),
                    "Notice must recommend regenerating");
            assertFalse(result.contains("Partial report"),
                    "Must not use consolidated notice when not truncated");
        }

        @Test
        @DisplayName("truncation with missing sections — consolidated graceful notice")
        void truncationWithMissingSections_consolidatedNotice() throws AiServiceException {
            // Gemini pattern: truncated AND Verdict section missing
            String partial = "## Executive Summary\n\nContent. BRIEF_VERDICT:PASS\n\n"
                    + "## Bottleneck Analysis\n\nTB.\n\n"
                    + "## Error Analysis\n\nErr.\n\n"
                    + "## Advanced Web Diagnostics\n\nDiag.\n\n"
                    + "## Root Cause Hypotheses\n\nHyp.\n\n"
                    + "## Recommendations\n\nRow.";
            String json = buildResponseWithUsage(partial, "stop", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            // Must use consolidated notice — NOT two separate notices
            assertTrue(result.contains("Partial report"),
                    "Consolidated notice header must appear");
            assertTrue(result.contains("Sections completed"),
                    "Consolidated notice must list completed sections");
            assertTrue(result.contains("Sections not reached"),
                    "Consolidated notice must list missing sections");
            assertTrue(result.contains("Verdict"),
                    "Missing section must be named");
            assertFalse(result.contains("Missing sections detected"),
                    "Must NOT show the separate missing-sections notice");
            assertFalse(result.contains("Report truncated"),
                    "Must NOT show the separate truncation notice");
            assertFalse(result.contains("max_tokens"),
                    "Must NOT recommend increasing max_tokens for fixed free-tier limits");
            assertTrue(result.contains("Cerebras") || result.contains("Mistral"),
                    "Must recommend alternative providers");
            assertTrue(result.contains("SLA verdict") || result.contains("transaction metrics"),
                    "Must reassure user that existing data is accurate");
        }

        @Test
        @DisplayName("truncation with all sections present — simple truncation notice only")
        void truncationAllSectionsPresent_simpleTruncationNotice() throws AiServiceException {
            String json = buildResponseWithUsage(FULL_CONTENT, "stop", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("Report truncated"),
                    "Simple truncation notice must appear");
            assertFalse(result.contains("Partial report"),
                    "Consolidated notice must NOT appear when all sections present");
            assertFalse(result.contains("Missing sections detected"),
                    "Missing sections notice must NOT appear");
        }

        @Test
        @DisplayName("missing sections notice is a Markdown blockquote")
        void missingSections_isBlockquote() throws AiServiceException {
            String partial = "## Executive Summary\n\nContent. VERDICT:PASS";
            String json = buildResponse(partial, "stop");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("\n> "),
                    "Notice must be a Markdown blockquote");
        }

        @Test
        @DisplayName("consolidated notice lists present sections correctly")
        void consolidatedNotice_listsPresentSections() throws AiServiceException {
            String partial = "## Executive Summary\n\nContent.\n\n"
                    + "## Bottleneck Analysis\n\nTB.";
            String json = buildResponseWithUsage(partial, "stop", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            int noticeStart = result.indexOf("Partial report");
            assertTrue(noticeStart >= 0, "Consolidated notice must be present");
            String noticeText = result.substring(noticeStart);
            assertTrue(noticeText.contains("Executive Summary"), "Present section listed");
            assertTrue(noticeText.contains("Bottleneck Analysis"), "Present section listed");
            assertTrue(noticeText.contains("Error Analysis"), "Missing section listed");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Truncation detection
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Truncation detection (finish_reason=length)")
    class TruncationDetection {

        @Test
        @DisplayName("finish_reason=length appends truncation notice after content")
        void finishReasonLength_appendsTruncationNotice() throws AiServiceException {
            String json = buildResponse(FULL_CONTENT, "length");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith(FULL_CONTENT),
                    "Original content must be preserved at the start");
            assertTrue(result.contains("⚠ Report truncated"),
                    "Truncation notice must be appended");
            assertTrue(result.contains("gemini") || result.contains("Gemini"),
                    "Notice must name the provider");
        }

        @Test
        @DisplayName("truncation notice is rendered as a Markdown blockquote")
        void truncationNotice_isMarkdownBlockquote() throws AiServiceException {
            String json = buildResponse("content", "length");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("\n> "),
                    "Truncation notice must be a Markdown blockquote (starts with '> ')");
        }

        @Test
        @DisplayName("original content is not modified — notice appended only")
        void originalContent_notModified() throws AiServiceException {
            // content without ## heading → skeleton prepended, then notice appended
            String original = "# Report\n\nSome data | with | pipes\n|---|---|---\n| a | b | c";
            String json = buildResponse(original, "length");
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith(SKELETON + original),
                    "Original content must appear verbatim after skeleton");
        }

        @Test
        @DisplayName("usage.completion_tokens == max_tokens with finish_reason=stop triggers truncation notice")
        void usageAtLimit_withFinishStop_appendsTruncationNotice() throws AiServiceException {
            String json = buildResponseWithUsage(FULL_CONTENT, "stop", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("⚠ Report truncated"),
                    "Truncation notice must be appended when completion_tokens == max_tokens");
        }

        @Test
        @DisplayName("usage.completion_tokens > max_tokens with finish_reason=stop triggers truncation notice")
        void usageOverLimit_withFinishStop_appendsTruncationNotice() throws AiServiceException {
            String json = buildResponseWithUsage(FULL_CONTENT, "stop", 1502, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.contains("⚠ Report truncated"),
                    "Truncation notice must be appended when completion_tokens > max_tokens");
        }

        @Test
        @DisplayName("usage.completion_tokens below max_tokens does not trigger truncation notice")
        void usageBelowLimit_noTruncationNotice() throws AiServiceException {
            String json = buildResponseWithUsage(FULL_CONTENT, "stop", 900, 1500);
            String result = service.extractAndValidateContent(json);
            assertFalse(result.contains("⚠ Report truncated"),
                    "No truncation notice expected when response completed normally");
            assertEquals(FULL_CONTENT, result);
        }

        @Test
        @DisplayName("finish_reason=length takes precedence regardless of usage field")
        void finishReasonLength_precedesUsageCheck() throws AiServiceException {
            String json = buildResponseWithUsage(FULL_CONTENT, "length", 1500, 1500);
            String result = service.extractAndValidateContent(json);
            assertTrue(result.startsWith(FULL_CONTENT),
                    "Original content must be preserved");
            assertEquals(1, countOccurrences(result, "⚠ Report truncated"),
                    "Truncation notice must appear exactly once");
        }

        @Test
        @DisplayName("malformed usage object does not cause exception — falls back gracefully")
        void malformedUsage_noException() throws AiServiceException {
            String json = "{"
                    + "\"choices\":[{"
                    + "\"message\":{\"role\":\"assistant\",\"content\":\"" + FULL_CONTENT_ESCAPED + "\"},"
                    + "\"finish_reason\":\"stop\","
                    + "\"index\":0"
                    + "}],"
                    + "\"usage\":{\"prompt_tokens\":200}"
                    + "}";
            String result = service.extractAndValidateContent(json);
            assertEquals(FULL_CONTENT, result,
                    "Malformed usage must be ignored; content returned as-is");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Error paths
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Error paths")
    class ErrorPaths {

        @Test
        @DisplayName("blank content throws AiServiceException")
        void blankContent_throwsException() {
            String json = buildResponse("   ", "stop");
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent(json),
                    "Blank content must throw AiServiceException");
        }

        @Test
        @DisplayName("malformed JSON throws AiServiceException")
        void malformedJson_throwsException() {
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent("{not valid json}"),
                    "Malformed JSON must throw AiServiceException");
        }

        @Test
        @DisplayName("empty choices array throws AiServiceException")
        void emptyChoices_throwsException() {
            String json = "{\"choices\":[]}";
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent(json),
                    "Empty choices array must throw AiServiceException");
        }

        @Test
        @DisplayName("missing choices field throws AiServiceException")
        void missingChoices_throwsException() {
            String json = "{\"id\":\"abc\",\"model\":\"gemini\"}";
            assertThrows(AiServiceException.class,
                    () -> service.extractAndValidateContent(json),
                    "Missing choices field must throw AiServiceException");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds a minimal OpenAI-compatible choices JSON string.
     *
     * @param content      the message content
     * @param finishReason the finish_reason value
     * @return JSON string
     */
    private static String buildResponse(String content, String finishReason) {
        // Escape content for embedding in JSON
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{"
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
                + "\"finish_reason\":\"" + finishReason + "\","
                + "\"index\":0"
                + "}]"
                + "}";
    }

    /**
     * Builds a minimal OpenAI-compatible JSON response that includes a {@code usage} block.
     * Used to test the usage-based truncation fallback.
     *
     * @param content           the message content
     * @param finishReason      the finish_reason value
     * @param completionTokens  completion_tokens to report in the usage block
     * @param configMaxTokens   max_tokens the service config was initialised with
     * @return JSON string
     */
    private static String buildResponseWithUsage(String content, String finishReason,
                                                 int completionTokens, int configMaxTokens) {
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return "{"
                + "\"choices\":[{"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped + "\"},"
                + "\"finish_reason\":\"" + finishReason + "\","
                + "\"index\":0"
                + "}],"
                + "\"usage\":{"
                + "\"prompt_tokens\":500,"
                + "\"completion_tokens\":" + completionTokens + ","
                + "\"total_tokens\":" + (500 + completionTokens)
                + "}"
                + "}";
    }

    /** Returns the number of non-overlapping occurrences of {@code sub} in {@code str}. */
    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx   = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}