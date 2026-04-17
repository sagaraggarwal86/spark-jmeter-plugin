package io.github.sagaraggarwal86.jmeter.listener.core;

import java.util.Objects;

/**
 * Immutable SLA threshold configuration snapshot.
 *
 * <p>A threshold value of {@code -1} indicates that threshold is disabled.
 * Callers must validate raw input before calling {@link #from}.</p>
 *
 * @since 4.6.0
 */
public final class SlaConfig {

    // ─────────────────────────────────────────────────────────────
    // Response-time metric selector
    // ─────────────────────────────────────────────────────────────

    /**
     * TPS threshold (minimum acceptable). {@code -1} = disabled.
     * Unlike error % and RT, TPS breaches when observed value is <em>below</em> threshold.
     */
    public final double tpsThreshold;

    /**
     * Error-rate threshold (0–99). {@code -1} = disabled.
     */
    public final double errorPctThreshold;

    // ─────────────────────────────────────────────────────────────
    // Fields
    // ─────────────────────────────────────────────────────────────
    /**
     * Which response-time column to compare. Never null.
     */
    public final RtMetric rtMetric;
    /**
     * Response-time threshold in milliseconds. {@code -1} = disabled.
     */
    public final long rtThresholdMs;
    /**
     * Currently configured percentile — drives the PNN column label.
     */
    public final int percentile;

    private SlaConfig(double tpsThreshold, double errorPctThreshold, RtMetric rtMetric,
                      long rtThresholdMs, int percentile) {
        this.tpsThreshold = tpsThreshold;
        this.errorPctThreshold = errorPctThreshold;
        this.rtMetric = rtMetric;
        this.rtThresholdMs = rtThresholdMs;
        this.percentile = percentile;
    }

    // ─────────────────────────────────────────────────────────────
    // Constructor (private — use factory methods)
    // ─────────────────────────────────────────────────────────────

    /**
     * Builds an {@code SlaConfig} from raw validated field strings.
     * Callers must validate input before invoking this method — invalid
     * strings will cause {@link NumberFormatException}.
     *
     * @param tpsSlaStr      blank = disabled; otherwise a positive numeric string
     * @param errorPctStr    blank = disabled; otherwise an integer string 1–99
     * @param rtThresholdStr blank = disabled; otherwise a positive integer string
     * @param rtMetric       which response-time column to compare; defaults to PNN
     * @param percentile     currently configured percentile (1–99)
     * @return populated {@code SlaConfig}
     */
    public static SlaConfig from(String tpsSlaStr, String errorPctStr, String rtThresholdStr,
                                 RtMetric rtMetric, int percentile) {
        double tps = -1;
        if (tpsSlaStr != null && !tpsSlaStr.isBlank()) {
            tps = Double.parseDouble(tpsSlaStr.trim());
        }
        double errorPct = -1;
        if (errorPctStr != null && !errorPctStr.isBlank()) {
            errorPct = Integer.parseInt(errorPctStr.trim());
        }
        long rtThreshold = -1;
        if (rtThresholdStr != null && !rtThresholdStr.isBlank()) {
            rtThreshold = Long.parseLong(rtThresholdStr.trim());
        }
        return new SlaConfig(
            tps,
            errorPct,
            Objects.requireNonNullElse(rtMetric, RtMetric.PNN),
            rtThreshold,
            percentile);
    }

    // ─────────────────────────────────────────────────────────────
    // State queries
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns a config with all thresholds disabled.
     *
     * @param percentile currently configured percentile
     * @return disabled config
     */
    public static SlaConfig disabled(int percentile) {
        return new SlaConfig(-1, -1, RtMetric.PNN, -1, percentile);
    }

    /**
     * Returns whether the error-rate threshold is active.
     *
     * @return {@code true} if the error-rate threshold is active
     */
    public boolean isTpsEnabled() {
        return tpsThreshold >= 0;
    }

    public boolean isErrorPctEnabled() {
        return errorPctThreshold >= 0;
    }

    // ─────────────────────────────────────────────────────────────
    // Factory methods
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns whether the response-time threshold is active.
     *
     * @return {@code true} if the response-time threshold is active
     */
    public boolean isRtEnabled() {
        return rtThresholdMs >= 0;
    }

    /**
     * Which response-time column is compared against {@link #rtThresholdMs}.
     * {@code AVG} → Avg (ms) column.
     * {@code PNN} → Pnn (ms) column (driven by the configured percentile).
     */
    public enum RtMetric {AVG, PNN}
}
