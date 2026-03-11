package com.personal.jmeter.ai;

import java.util.Objects;

/**
 * Parameter object grouping scenario-level context for an AI prompt.
 *
 * <p>Introduced to keep {@link PromptBuilder#build} within the three-parameter limit
 * required by the method design standard.</p>
 *
 * @param users                virtual user count label (blank if unknown)
 * @param scenarioName         test plan name (blank if unknown)
 * @param scenarioDesc         test plan comment / description (blank if unknown)
 * @param startTime            formatted test start time (blank if unknown)
 * @param endTime              formatted test end time (blank if unknown)
 * @param duration             formatted test duration (blank if unknown)
 * @param threadGroupName      first thread group name (blank if unknown)
 * @param configuredPercentile percentile configured in the UI (1–99)
 * @param errorSlaThresholdPct user-configured error % SLA; "Not configured" if disabled
 * @param rtSlaThresholdMs     user-configured response time SLA in ms; "Not configured" if disabled
 * @param rtSlaMetric          response time metric the RT SLA applies to (e.g. "Avg (ms)", "P90 (ms)")
 */
public record PromptRequest(
        String users,
        String scenarioName,
        String scenarioDesc,
        String startTime,
        String endTime,
        String duration,
        String threadGroupName,
        int    configuredPercentile,
        String errorSlaThresholdPct,
        String rtSlaThresholdMs,
        String rtSlaMetric) {

    private static final String NOT_CONFIGURED = "Not configured";

    /**
     * Compact canonical constructor — normalises null String fields to empty strings,
     * and null SLA sentinel fields to "Not configured".
     */
    public PromptRequest {
        users               = Objects.requireNonNullElse(users, "");
        scenarioName        = Objects.requireNonNullElse(scenarioName, "");
        scenarioDesc        = Objects.requireNonNullElse(scenarioDesc, "");
        startTime           = Objects.requireNonNullElse(startTime, "");
        endTime             = Objects.requireNonNullElse(endTime, "");
        duration            = Objects.requireNonNullElse(duration, "");
        threadGroupName     = Objects.requireNonNullElse(threadGroupName, "");
        errorSlaThresholdPct = Objects.requireNonNullElse(errorSlaThresholdPct, NOT_CONFIGURED);
        rtSlaThresholdMs    = Objects.requireNonNullElse(rtSlaThresholdMs, NOT_CONFIGURED);
        rtSlaMetric         = Objects.requireNonNullElse(rtSlaMetric, NOT_CONFIGURED);
    }

    /**
     * Returns a {@code PromptRequest} with all fields empty / default.
     * Used by tests and as a safe fallback.
     *
     * @return empty request instance
     */
    public static PromptRequest empty() {
        return new PromptRequest(
                "", "", "", "", "", "", "",
                90,
                NOT_CONFIGURED, NOT_CONFIGURED, NOT_CONFIGURED);
    }
}