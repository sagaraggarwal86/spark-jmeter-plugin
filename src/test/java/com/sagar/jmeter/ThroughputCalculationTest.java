package com.sagar.jmeter;

import com.Sagar.jmeter.data.AggregateResult;
import com.Sagar.jmeter.data.JTLRecord;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify throughput calculation is correct
 */
class ThroughputCalculationTest {

    @Test
    void testThroughputWithSequentialRequests() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        // 3 sequential requests over 2.1 seconds
        JTLRecord r1 = createRecord(1000, 100, true);  // 1.0s - 1.1s
        JTLRecord r2 = createRecord(2000, 100, true);  // 2.0s - 2.1s
        JTLRecord r3 = createRecord(3000, 100, true);  // 3.0s - 3.1s

        result.addSample(r1);
        result.addSample(r2);
        result.addSample(r3);

        // Time span = 3000 - 1000 + 100 = 2100ms = 2.1s
        // Throughput = 3 / 2.1 = 1.43 req/sec
        double throughput = result.getThroughput();
        assertEquals(1.43, throughput, 0.01, "Sequential requests throughput");
    }

    @Test
    void testThroughputWithConcurrentRequests() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        // 3 overlapping requests
        JTLRecord r1 = createRecord(1000, 500, true);  // 1.0s - 1.5s
        JTLRecord r2 = createRecord(1100, 500, true);  // 1.1s - 1.6s
        JTLRecord r3 = createRecord(1200, 500, true);  // 1.2s - 1.7s

        result.addSample(r1);
        result.addSample(r2);
        result.addSample(r3);

        // Time span = 1200 - 1000 + 500 = 700ms = 0.7s
        // Throughput = 3 / 0.7 = 4.29 req/sec
        double throughput = result.getThroughput();
        assertEquals(4.29, throughput, 0.01, "Concurrent requests throughput");
    }

    @Test
    void testThroughputWithSingleRequest() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        JTLRecord r1 = createRecord(1000, 200, true);

        result.addSample(r1);

        // Time span = 0 + 200 = 200ms = 0.2s
        // Throughput = 1 / 0.2 = 5 req/sec
        double throughput = result.getThroughput();
        assertEquals(5.0, throughput, 0.01, "Single request throughput");
    }

    @Test
    void testErrorPercentageCalculation() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        // 10 requests, 2 failures
        for (int i = 0; i < 8; i++) {
            result.addSample(createRecord(1000 + i * 100, 50, true));
        }
        result.addSample(createRecord(1800, 50, false));  // Failed
        result.addSample(createRecord(1900, 50, false));  // Failed

        double errorPercentage = result.getErrorPercentage();
        assertEquals(20.0, errorPercentage, 0.01, "Error percentage should be 20%");
    }

    @Test
    void testErrorPercentageWithNoErrors() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        result.addSample(createRecord(1000, 100, true));
        result.addSample(createRecord(2000, 100, true));
        result.addSample(createRecord(3000, 100, true));

        double errorPercentage = result.getErrorPercentage();
        assertEquals(0.0, errorPercentage, 0.01, "Error percentage should be 0%");
    }

    @Test
    void testErrorPercentageWithAllErrors() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        result.addSample(createRecord(1000, 100, false));
        result.addSample(createRecord(2000, 100, false));
        result.addSample(createRecord(3000, 100, false));

        double errorPercentage = result.getErrorPercentage();
        assertEquals(100.0, errorPercentage, 0.01, "Error percentage should be 100%");
    }

    @Test
    void testThroughputVsOldCalculation() {
        AggregateResult result = new AggregateResult();
        result.setLabel("Test");

        // 5 requests with different patterns
        result.addSample(createRecord(1000, 500, true));
        result.addSample(createRecord(1200, 300, true));
        result.addSample(createRecord(1400, 100, true));
        result.addSample(createRecord(2500, 400, true));
        result.addSample(createRecord(3000, 200, true));

        // NEW (CORRECT) calculation:
        // minTimestamp = 1000
        // maxTimestamp = 3000
        // maxElapsed = 200
        // timeSpan = 3000 - 1000 + 200 = 2200ms = 2.2s
        // throughput = 5 / 2.2 = 2.27 req/sec
        double newThroughput = result.getThroughput();
        assertEquals(2.27, newThroughput, 0.01, "New throughput calculation");

        // OLD (WRONG) calculation would have been:
        // totalTime = 500 + 300 + 100 + 400 + 200 = 1500ms = 1.5s
        // throughput = 5 / 1.5 = 3.33 req/sec (WRONG!)
        double oldWrongThroughput = 5.0 / 1.5;
        assertEquals(3.33, oldWrongThroughput, 0.01, "Old wrong calculation");

        // Verify they are different
        assertNotEquals(oldWrongThroughput, newThroughput, 0.1,
                "New calculation should be different from old incorrect calculation");
    }

    /**
     * Helper method to create a JTLRecord
     */
    private JTLRecord createRecord(long timestamp, long elapsed, boolean success) {
        JTLRecord record = new JTLRecord();
        record.setTimeStamp(timestamp);
        record.setElapsed(elapsed);
        record.setSuccess(success);
        record.setLabel("TestRequest");
        record.setResponseCode(success ? "200" : "500");
        record.setBytes(1000);
        return record;
    }
}
