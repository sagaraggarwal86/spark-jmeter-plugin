package com.personal.jmeter.listener;

import org.apache.jmeter.reporters.ResultCollector;

/**
 * Backend collector for the JAAR — JTL AI Analysis &amp; Reporting plugin.
 *
 * <p>Persists all UI configuration properties into the .jmx file so that
 * the panel state is fully restored on tree navigation.
 * No live metric collection — all data comes from uploaded JTL files.</p>
 */
public class ListenerCollector extends ResultCollector {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    // ── Plugin identity ───────────────────────────────────────────
    /** Display name shown in the JMeter listener menu. */
    public static final String PLUGIN_NAME = "JAAR - JTL AI Analysis & Reporting";

    /** GitHub repository URL — single source of truth for the help link. */
    public static final String HELP_URL =
            "https://github.com/sagaraggarwal86/jaar-jmeter-plugin";

    // ── Filter fields ────────────────────────────────────────────
    /** JMeter property key for the start-offset filter (seconds). */
    public static final String PROP_START_OFFSET = "startOffset";
    /** JMeter property key for the end-offset filter (seconds). */
    public static final String PROP_END_OFFSET   = "endOffset";
    /** JMeter property key for the configured percentile. */
    public static final String PROP_PERCENTILE   = "percentile";

    // ── SLA fields ───────────────────────────────────────────────
    /** JMeter property key for the error % SLA threshold field. */
    public static final String PROP_ERROR_PCT_SLA    = "errorPctSla";
    /** JMeter property key for the response time SLA threshold field (ms). */
    public static final String PROP_RT_THRESHOLD_SLA = "rtThresholdSla";
    /**
     * JMeter property key for the RT metric combo selection index.
     * Stored as an integer: 0 = Avg (ms), 1 = Pnn (ms).
     */
    public static final String PROP_RT_METRIC        = "rtMetric";

    // ── Chart / search fields ────────────────────────────────────
    /** JMeter property key for the chart interval field (seconds). */
    public static final String PROP_CHART_INTERVAL   = "chartInterval";
    /** JMeter property key for the transaction search text. */
    public static final String PROP_SEARCH           = "search";
    /** JMeter property key for the regex checkbox state (boolean). */
    public static final String PROP_REGEX            = "regex";
    /**
     * JMeter property key for the filter mode combo selection index.
     * Stored as an integer: 0 = Include, 1 = Exclude.
     */
    public static final String PROP_FILTER_MODE      = "filterMode";

    // ── File and column state ────────────────────────────────────
    /** JMeter property key for the last loaded JTL file path. */
    public static final String PROP_LAST_FILE        = "lastFile";
    /**
     * JMeter property key for column visibility state.
     * Stored as a comma-separated boolean string, one value per column,
     * e.g. {@code "true,true,false,true,true,true,false,true,true,true,true"}.
     */
    public static final String PROP_COL_VISIBILITY   = "colVisibility";
}