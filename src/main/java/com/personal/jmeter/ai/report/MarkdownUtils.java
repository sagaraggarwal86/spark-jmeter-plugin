package com.personal.jmeter.ai.report;

/**
 * Stateless utility methods for post-processing AI-generated Markdown.
 *
 * <p>Centralises the machine-verdict protocol so that both the CLI pipeline
 * ({@code CliReportPipeline}) and the Swing UI pipeline ({@code AiReportCoordinator})
 * share a single, tested implementation.</p>
 *
 * <h3>Two-token truncation-resilience protocol</h3>
 * <p>The AI emits two machine-readable verdict tokens:</p>
 * <ol>
 *   <li>{@code BRIEF_VERDICT:PASS} / {@code BRIEF_VERDICT:FAIL} — emitted
 *       immediately after the Executive Summary section. Acts as a truncation
 *       anchor: if the model is cut off before completing the Verdict section,
 *       this token is still present and can be detected.</li>
 *   <li>{@code VERDICT:PASS} / {@code VERDICT:FAIL} — emitted as the absolute
 *       last line of the Verdict section. Canonical token when the response is
 *       complete.</li>
 * </ol>
 *
 * <p>{@link #extractVerdict(String)} scans for {@code BRIEF_VERDICT:} from the
 * top of the document first (resilient path), then falls back to scanning for
 * {@code VERDICT:} from the bottom (canonical path). This means a complete
 * response uses the canonical token, and a truncated response uses the anchor.</p>
 *
 * <p>Neither token is ever visible in the rendered HTML report.
 * {@link #stripVerdictLine(String)} removes all lines starting with either
 * {@code VERDICT:} or {@code BRIEF_VERDICT:} before rendering.</p>
 * @since 4.6.0
 */
public final class MarkdownUtils {

    private MarkdownUtils() {
    }

    // ─────────────────────────────────────────────────────────────
    // Verdict extraction
    // ─────────────────────────────────────────────────────────────

    /**
     * Extracts the machine verdict from AI-generated markdown using a two-pass
     * truncation-resilient strategy.
     *
     * <h4>Pass 1 — canonical (bottom scan)</h4>
     * <p>Scans the last non-blank line for {@code VERDICT:PASS} or
     * {@code VERDICT:FAIL}. This is the primary signal for a complete response.</p>
     *
     * <h4>Pass 2 — resilient fallback (top scan)</h4>
     * <p>If Pass 1 finds no {@code VERDICT:} token (i.e. the response was
     * truncated before the Verdict section), scans all lines from the top for
     * the first {@code BRIEF_VERDICT:PASS} or {@code BRIEF_VERDICT:FAIL} token.
     * This token is emitted immediately after the Executive Summary, so it
     * survives truncation in all but the most extreme cases.</p>
     *
     * <p>Returns {@code "UNDECISIVE"} when neither token is found.</p>
     *
     * @param markdown raw AI-generated markdown; may be null
     * @return {@code "PASS"}, {@code "FAIL"}, or {@code "UNDECISIVE"}
     */
    public static String extractVerdict(String markdown) {
        if (markdown == null || markdown.isBlank()) return "UNDECISIVE";
        String[] lines = markdown.split("\n");
        String canonical = findCanonicalVerdict(lines);
        if (canonical != null) return canonical;
        String fallback = findBriefVerdict(lines);
        return fallback != null ? fallback : "UNDECISIVE";
    }

    // ─────────────────────────────────────────────────────────────
    // Verdict source detection
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the resolution path used by {@link #extractVerdict(String)}.
     *
     * <ul>
     *   <li>{@code "CANONICAL"} — {@code VERDICT:} token found as the last
     *       non-blank line; the AI response completed fully.</li>
     *   <li>{@code "FALLBACK"} — {@code VERDICT:} absent; {@code BRIEF_VERDICT:}
     *       anchor token found early in the document; the response was truncated
     *       before the Verdict section but the anchor survived.</li>
     *   <li>{@code "UNDECISIVE"} — neither token present; the response was
     *       truncated before the {@code BRIEF_VERDICT:} anchor or the AI omitted
     *       both tokens.</li>
     * </ul>
     *
     * <p>Callers should log this value at INFO level on every run so that
     * truncation events are visible in the log without requiring DEBUG output.</p>
     *
     * @param markdown raw AI-generated markdown; may be null
     * @return {@code "CANONICAL"}, {@code "FALLBACK"}, or {@code "UNDECISIVE"}
     */
    public static String verdictSource(String markdown) {
        if (markdown == null || markdown.isBlank()) return "UNDECISIVE";
        String[] lines = markdown.split("\n");
        if (findCanonicalVerdict(lines) != null) return "CANONICAL";
        if (findBriefVerdict(lines) != null) return "FALLBACK";
        return "UNDECISIVE";
    }

    // ─────────────────────────────────────────────────────────────
    // Verdict stripping
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the markdown with all machine verdict lines removed so neither
     * token is ever rendered as visible HTML.
     *
     * <p>Strips every line whose trimmed content starts with {@code VERDICT:}
     * or {@code BRIEF_VERDICT:}. Both tokens must be stripped because
     * {@code BRIEF_VERDICT:} appears mid-document (after Executive Summary)
     * and {@code VERDICT:} appears at the end. Trailing whitespace is trimmed
     * from the result.</p>
     *
     * <p>If neither token is present — e.g. the AI omitted both — the original
     * markdown is returned unchanged; the method never throws.</p>
     *
     * @param markdown raw AI-generated markdown; may be null
     * @return markdown with all verdict token lines removed and trailing
     * whitespace trimmed; {@code markdown} itself if null or blank
     */
    public static String stripVerdictLine(String markdown) {
        if (markdown == null || markdown.isBlank()) return markdown;
        String[] lines = markdown.split("\n", -1);
        boolean stripped = false;
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String normalised = normaliseTokenLine(raw);
            if (normalised.equals("VERDICT:PASS") || normalised.equals("VERDICT:FAIL")
                    || normalised.equals("BRIEF_VERDICT:PASS") || normalised.equals("BRIEF_VERDICT:FAIL")) {
                // Token is on its own line — omit the entire line
                stripped = true;
            } else if (normalised.startsWith("VERDICT:") || normalised.startsWith("BRIEF_VERDICT:")) {
                // Token is a standalone line starting with the prefix — omit
                stripped = true;
            } else {
                // Check for inline token at end of sentence — strip just the token suffix
                String stripped_line = raw;
                for (String token : new String[]{"VERDICT:PASS", "VERDICT:FAIL", "BRIEF_VERDICT:PASS", "BRIEF_VERDICT:FAIL"}) {
                    String norm = normalised;
                    if (norm.endsWith(token)) {
                        // Remove the token and any preceding space/punctuation from the raw line
                        int idx = raw.lastIndexOf(token);
                        if (idx > 0) {
                            stripped_line = raw.substring(0, idx).stripTrailing();
                            stripped = true;
                        }
                        break;
                    }
                }
                sb.append(stripped_line).append("\n");
            }
        }
        return stripped ? sb.toString().stripTrailing() : markdown;
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Scans the last non-blank line for a {@code VERDICT:PASS} or {@code VERDICT:FAIL}
     * token (on its own line or inline at the end of a sentence).
     *
     * @param lines pre-split markdown lines
     * @return {@code "PASS"}, {@code "FAIL"}, or {@code null} if not found
     */
    private static String findCanonicalVerdict(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = normaliseTokenLine(lines[i]);
            if (!line.isEmpty()) {
                if (line.equals("VERDICT:PASS") || line.endsWith("VERDICT:PASS")) return "PASS";
                if (line.equals("VERDICT:FAIL") || line.endsWith("VERDICT:FAIL")) return "FAIL";
                break; // last non-blank line has no VERDICT: token — stop
            }
        }
        return null;
    }

    /**
     * Scans from the top for a {@code BRIEF_VERDICT:PASS} or {@code BRIEF_VERDICT:FAIL}
     * truncation-anchor token.
     *
     * @param lines pre-split markdown lines
     * @return {@code "PASS"}, {@code "FAIL"}, or {@code null} if not found
     */
    private static String findBriefVerdict(String[] lines) {
        for (String raw : lines) {
            String line = normaliseTokenLine(raw);
            if (line.equals("BRIEF_VERDICT:PASS") || line.endsWith("BRIEF_VERDICT:PASS")) return "PASS";
            if (line.equals("BRIEF_VERDICT:FAIL") || line.endsWith("BRIEF_VERDICT:FAIL")) return "FAIL";
        }
        return null;
    }

    /**
     * Trims whitespace and strips markdown emphasis markers ({@code *}, {@code _},
     * {@code `}) from both ends of a line before token comparison.
     *
     * <p>Some models wrap the machine verdict token in bold or italic markers,
     * e.g. {@code **VERDICT:FAIL**} or {@code *BRIEF_VERDICT:PASS*}. This helper
     * normalises such lines so that the token-matching logic in
     * {@link #extractVerdict}, {@link #verdictSource}, and
     * {@link #stripVerdictLine} correctly recognises them regardless of
     * formatting decoration.</p>
     *
     * @param raw a single raw line from the AI markdown
     * @return the line with leading/trailing whitespace and emphasis markers removed
     */
    private static String normaliseTokenLine(String raw) {
        if (raw == null) return "";
        // Strip whitespace first, then markdown emphasis characters from both ends
        String s = raw.trim();
        int start = 0;
        int end = s.length();
        while (start < end && isEmphasisChar(s.charAt(start))) start++;
        while (end > start && isEmphasisChar(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isEmphasisChar(char c) {
        return c == '*' || c == '_' || c == '`';
    }
}