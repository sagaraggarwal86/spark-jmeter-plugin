package com.personal.jmeter.listener.core;

/**
 * Shared numeric-parsing utilities for table cell values.
 *
 * <p>Centralises the three parse helpers that were previously duplicated
 * identically across {@link CsvExporter}, {@link SlaRowRenderer}, and
 * {@code HtmlReportRenderer}: {@link #parseErrorRate}, {@link #parseDouble},
 * and {@link #parseMs}. All callers delegate here so any future fix to the
 * parsing logic requires a single edit.</p>
 *
 * <p>All methods are {@code public static} and this class is non-instantiable.</p>
 *
 * @since 4.7.0
 */
public final class CellValueParser {

    private CellValueParser() {
        throw new AssertionError("CellValueParser is a utility class");
    }

    // ─────────────────────────────────────────────────────────────
    // Object-accepting overloads (used by Swing table renderers and exporters)
    // ─────────────────────────────────────────────────────────────

    /**
     * Strips a trailing {@code %} from {@code val.toString()} and parses the result.
     * Returns {@code 0.0} when {@code val} is {@code null} or not parseable.
     *
     * @param val raw cell value from a {@link javax.swing.table.DefaultTableModel}
     * @return parsed error-rate percentage as a double
     */
    public static double parseErrorRate(Object val) {
        if (val == null) return 0.0;
        return parseErrorRate(val.toString());
    }

    /**
     * Parses a plain numeric cell value.
     * Returns {@code 0.0} when {@code val} is {@code null} or not parseable.
     *
     * @param val raw cell value from a {@link javax.swing.table.DefaultTableModel}
     * @return parsed numeric value as a double
     */
    public static double parseDouble(Object val) {
        if (val == null) return 0.0;
        return parseDouble(val.toString());
    }

    // ─────────────────────────────────────────────────────────────
    // String-accepting overloads (used by HTML report renderer)
    // ─────────────────────────────────────────────────────────────

    /**
     * Strips a trailing {@code %} from {@code s} and parses the result.
     * Returns {@code 0.0} when {@code s} is {@code null}, blank, or not parseable.
     *
     * @param s error-rate cell string (e.g. {@code "1.23%"})
     * @return parsed error-rate percentage as a double
     */
    public static double parseErrorRate(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parses a plain numeric string.
     * Returns {@code 0.0} when {@code s} is {@code null}, blank, or not parseable.
     *
     * @param s numeric cell string
     * @return parsed value as a double
     */
    public static double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * Parses a millisecond cell string, stripping thousands-separator commas
     * before parsing (e.g. {@code "1,024"} → {@code 1024.0}).
     * Returns {@code 0.0} when {@code s} is {@code null}, blank, or not parseable.
     *
     * @param s ms cell string (e.g. {@code "312"} or {@code "1,024"})
     * @return parsed value as a double
     */
    public static double parseMs(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
