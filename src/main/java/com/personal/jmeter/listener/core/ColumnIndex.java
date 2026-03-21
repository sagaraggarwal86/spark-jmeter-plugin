package com.personal.jmeter.listener.core;

/**
 * Column index constants and column definitions for the JAAR results table.
 *
 * <p>Extracted from {@code AggregateReportPanel} to break the circular
 * dependency between {@code listener.core} and {@code listener.gui}:
 * core classes ({@link TablePopulator}, {@link CsvExporter}) reference
 * these constants without depending on any gui class.</p>
 *
 * <p>Single source of truth for column ordering — any change here
 * propagates automatically to the table, CSV export, and AI prompt.</p>
 * @since 4.6.0
 */
public final class ColumnIndex {

    /**
     * All column header names in display order.
     */
    public static final String[] ALL_COLUMNS = {
            "Transaction Name", "Count", "Passed",
            "Failed", "Avg (ms)", "Min (ms)",
            "Max (ms)", "P90 (ms)", "Std. Dev.", "Error Rate", "TPS",
            "Received KB/Sec", "Avg Bytes"
    };

    /**
     * Model index of the configurable percentile column.
     */
    public static final int PERCENTILE_COL_INDEX = 7;

    /**
     * Model index of Avg (ms) — used by {@link SlaRowRenderer}.
     */
    public static final int AVG_COL_INDEX = 4;

    /**
     * Model index of Error Rate — used by {@link SlaRowRenderer}.
     */
    public static final int ERROR_RATE_COL_INDEX = 9;

    /**
     * Model index of Transaction Name — used by {@link SlaRowRenderer}.
     */
    public static final int NAME_COL_INDEX = 0;

    private ColumnIndex() { /* constants-only — not instantiable */ }
}
