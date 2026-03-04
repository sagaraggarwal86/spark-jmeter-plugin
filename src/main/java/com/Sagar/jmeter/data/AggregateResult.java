package com.Sagar.jmeter.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aggregated statistics for a group of samples with the same label
 */
public class AggregateResult {
    private String label;
    private int count;
    private long totalTime;
    private long minTime = Long.MAX_VALUE;
    private long maxTime = Long.MIN_VALUE;
    private final List<Long> times = new ArrayList<>();
    private int errorCount;
    private long totalBytes;

    // For accurate throughput calculation
    private long minTimestamp = Long.MAX_VALUE;
    private long maxTimestamp = Long.MIN_VALUE;
    private long maxElapsedAtMaxTimestamp = 0;

    public void addSample(JTLRecord record) {
        count++;
        long elapsed = record.getElapsed();
        long timestamp = record.getTimeStamp();

        totalTime += elapsed;
        times.add(elapsed);

        if (elapsed < minTime) minTime = elapsed;
        if (elapsed > maxTime) maxTime = elapsed;

        // Track timestamp range for throughput calculation
        if (timestamp < minTimestamp) minTimestamp = timestamp;
        if (timestamp > maxTimestamp) {
            maxTimestamp = timestamp;
            maxElapsedAtMaxTimestamp = elapsed;
        }

        if (!record.isSuccess()) {
            errorCount++;
        }

        totalBytes += record.getBytes();
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getCount() {
        return count;
    }

    public double getAverage() {
        return count > 0 ? (double) totalTime / count : 0;
    }

    public long getMin() {
        return count > 0 ? minTime : 0;
    }

    public long getMax() {
        return count > 0 ? maxTime : 0;
    }

    public double getPercentile(int percentile) {
        if (times.isEmpty()) return 0;

        Collections.sort(times);
        int index = (int) Math.ceil(percentile / 100.0 * times.size()) - 1;
        if (index < 0) index = 0;
        if (index >= times.size()) index = times.size() - 1;

        return times.get(index);
    }

    public double getStdDev() {
        if (count <= 1) return 0;

        double avg = getAverage();
        double sumSquaredDiff = 0;

        for (long time : times) {
            double diff = time - avg;
            sumSquaredDiff += diff * diff;
        }

        return Math.sqrt(sumSquaredDiff / (count - 1));
    }

    public double getErrorPercentage() {
        return count > 0 ? (errorCount * 100.0 / count) : 0;
    }

    public double getThroughput() {
        if (count == 0) return 0;
        if (minTimestamp == Long.MAX_VALUE || maxTimestamp == Long.MIN_VALUE) return 0;

        // Calculate throughput as requests per second based on actual time span
        // Time span = from first request start to last request end
        long timeSpanMs = maxTimestamp - minTimestamp + maxElapsedAtMaxTimestamp;

        // Handle edge case: single request or all at same timestamp
        if (timeSpanMs <= 0) {
            // If all requests happened at the same timestamp, use total elapsed time
            timeSpanMs = totalTime;
        }

        double timeSpanSeconds = timeSpanMs / 1000.0;
        return timeSpanSeconds > 0 ? count / timeSpanSeconds : 0;
    }

    public long getAvgBytes() {
        return count > 0 ? totalBytes / count : 0;
    }
}
