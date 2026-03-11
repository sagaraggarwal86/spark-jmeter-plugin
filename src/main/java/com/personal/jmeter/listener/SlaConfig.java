package com.personal.jmeter.listener;

import java.util.Objects;

/**
 * Immutable SLA threshold configuration snapshot.
 *
 * <p>A threshold value of {@code -1} indicates that threshold is disabled.
 * Callers must validate raw input before calling {@link #from}.</p>
 */
public final class SlaConfig {

    // ─────────────────────────────────────────────────────────────
    // Response-time metric selector
    // ─────────────────────────────────────────────────────────────

    /**
     * Which response-time column is compared against {@link #rtThresholdMs}.
     * {@code AVG} → Avg (ms) column.
     * {@code PNN} → Pnn (ms) column (driven by the configured percentile).
     */
    public enum RtMetric { AVG, PNN }

    // ─────────────────────────────────────────────────────────────
    // Fields
    // ─────────────────────────────────────────────────────────────

    /** Error-rate threshold (0–99). {@code -1} = disabled. */
    public final double errorPctThreshold;

    /** Which response-time column to compare. Never null. */
    public final RtMetric rtMetric;

    /** Response-time threshold in milliseconds. {@code -1} = disabled. */
    public final long rtThresholdMs;

    /** Currently configured percentile — drives the PNN column label. */
    public final int percentile;

    // ─────────────────────────────────────────────────────────────
    // Constructor (private — use factory methods)
    // ─────────────────────────────────────────────────────────────

    private SlaConfig(double errorPctThreshold, RtMetric rtMetric,
                      long rtThresholdMs, int percentile) {
        this.errorPctThreshold = errorPctThreshold;
        this.rtMetric          = rtMetric;
        this.rtThresholdMs     = rtThresholdMs;
        this.percentile        = percentile;
    }

    // ─────────────────────────────────────────────────────────────
    // State queries
    // ─────────────────────────────────────────────────────────────

    /** @return {@code true} if the error-rate threshold is active. */
    public boolean isErrorPctEnabled() { return errorPctThreshold >= 0; }

    /** @return {@code true} if the response-time threshold is active. */
    public boolean isRtEnabled() { return rtThresholdMs >= 0; }

    // ─────────────────────────────────────────────────────────────
    // Factory methods
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds an {@code SlaConfig} from raw validated field strings.
     * Callers must validate input before invoking this method — invalid
     * strings will cause {@link NumberFormatException}.
     *
     * @param errorPctStr    blank = disabled; otherwise an integer string 1–99
     * @param rtThresholdStr blank = disabled; otherwise a positive integer string
     * @param rtMetric       which response-time column to compare; defaults to PNN
     * @param percentile     currently configured percentile (1–99)
     * @return populated {@code SlaConfig}
     */
    static SlaConfig from(String errorPctStr, String rtThresholdStr,
                          RtMetric rtMetric, int percentile) {
        double errorPct = -1;
        if (errorPctStr != null && !errorPctStr.isBlank()) {
            errorPct = Integer.parseInt(errorPctStr.trim());
        }
        long rtThreshold = -1;
        if (rtThresholdStr != null && !rtThresholdStr.isBlank()) {
            rtThreshold = Long.parseLong(rtThresholdStr.trim());
        }
        return new SlaConfig(
                errorPct,
                Objects.requireNonNullElse(rtMetric, RtMetric.PNN),
                rtThreshold,
                percentile);
    }

    /**
     * Returns a config with all thresholds disabled.
     *
     * @param percentile currently configured percentile
     * @return disabled config
     */
    static SlaConfig disabled(int percentile) {
        return new SlaConfig(-1, RtMetric.PNN, -1, percentile);
    }
}