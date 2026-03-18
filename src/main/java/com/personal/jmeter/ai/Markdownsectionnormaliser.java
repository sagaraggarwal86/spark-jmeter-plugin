package com.personal.jmeter.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-processes AI-generated Markdown to enforce structural section heading
 * consistency.
 *
 * <h3>Context</h3>
 * <p>The report pipeline seeds {@code ## Executive Summary\n\n} as an assistant
 * prefill before generation. Some models (notably Mistral) repeat this heading
 * in their continuation, producing:</p>
 * <pre>
 *   ## Executive Summary        ← from skeleton prefill (no content follows)
 *   ## Executive Summary        ← model wrote it again (content follows)
 *   The load test ran for...
 * </pre>
 * <p>{@link #normalise(String)} removes all but the <em>last</em> occurrence of
 * each duplicate heading. The last occurrence is always the one the model wrote
 * with actual content after it; the earlier occurrences are skeleton artefacts
 * with no content between them and the next heading.</p>
 *
 * <h3>Design principle</h3>
 * <p>This class is a structural safety net only — it never generates or moves
 * content. Only exact heading-line matches are considered; partial matches and
 * content lines are never touched.</p>
 */
final class MarkdownSectionNormaliser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSectionNormaliser.class);

    /** All seven expected section headings, used for duplicate detection. */
    static final String[] EXPECTED_HEADINGS = {
            "## Executive Summary",
            "## Bottleneck Analysis",
            "## Error Analysis",
            "## Advanced Web Diagnostics",
            "## Root Cause Hypotheses",
            "## Recommendations",
            "## Verdict",
    };

    private MarkdownSectionNormaliser() { /* static utility */ }

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Removes all but the last occurrence of any duplicated expected section heading.
     *
     * <p>The last occurrence is kept because it is the one the model wrote with
     * actual content following it. Earlier occurrences are skeleton prefill
     * artefacts that have no content between them and the next heading.</p>
     *
     * <p>If the markdown is null, blank, or contains no duplicates, the input
     * is returned unchanged (same reference).</p>
     *
     * @param markdown raw AI-generated Markdown; may be null
     * @return Markdown with earlier duplicate headings removed;
     *         the original string if no duplicates were found
     */
    static String normalise(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;

        String[] lines = markdown.split("\n", -1);

        // Pass 1 — find the last line index of each expected heading.
        // Headings that appear only once are not in this map (no dedup needed).
        java.util.Map<String, Integer> lastIndex = new java.util.HashMap<>();
        java.util.Map<String, Integer> count     = new java.util.HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            for (String h : EXPECTED_HEADINGS) {
                if (trimmed.equals(h)) {
                    lastIndex.put(h, i);
                    count.merge(h, 1, Integer::sum);
                    break;
                }
            }
        }

        // Pass 2 — emit lines, skipping any heading occurrence that is not the last.
        boolean       changed = false;
        StringBuilder sb      = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            boolean isDuplicate = false;
            for (String h : EXPECTED_HEADINGS) {
                if (trimmed.equals(h) && count.getOrDefault(h, 1) > 1 && lastIndex.get(h) != i) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) {
                log.info("normalise: removed earlier duplicate heading '{}' at line {}", trimmed, i);
                changed = true;
                continue;
            }
            sb.append(lines[i]).append("\n");
        }

        if (!changed) return markdown;
        String result = sb.toString();
        return result.endsWith("\n") && !markdown.endsWith("\n")
                ? result.substring(0, result.length() - 1)
                : result;
    }
}
