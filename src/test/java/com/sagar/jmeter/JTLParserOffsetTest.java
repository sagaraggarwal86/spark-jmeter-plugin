package com.sagar.jmeter;

import com.Sagar.jmeter.data.AggregateResult;
import com.Sagar.jmeter.parser.JTLParser;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify offset filtering works correctly
 */
class JTLParserOffsetTest {

    @Test
    void testOffsetFiltering() throws Exception {
        // Path to the example JTL file
        String jtlPath = "src/main/java/example.jtl";
        File jtlFile = new File(jtlPath);
        assertTrue(jtlFile.exists(), "JTL file should exist");

        JTLParser parser = new JTLParser();

        // Test 1: No filters - should get all records
        JTLParser.FilterOptions noFilterOptions = new JTLParser.FilterOptions();
        Map<String, AggregateResult> allResults = parser.parse(jtlPath, noFilterOptions);
        assertFalse(allResults.isEmpty(), "Should have results without filters");

        int totalRecords = allResults.values().stream()
                .mapToInt(AggregateResult::getCount)
                .sum();
        System.out.println("Total records without filter: " + totalRecords);

        // Test 2: With start offset = 1 second (skip first second)
        JTLParser.FilterOptions startOffsetOptions = new JTLParser.FilterOptions();
        startOffsetOptions.startOffset = 1;
        Map<String, AggregateResult> startOffsetResults = parser.parse(jtlPath, startOffsetOptions);

        int startOffsetRecords = startOffsetResults.values().stream()
                .mapToInt(AggregateResult::getCount)
                .sum();
        System.out.println("Records with startOffset=1: " + startOffsetRecords);

        assertTrue(startOffsetRecords < totalRecords,
                "Start offset should filter out some records");

        // Test 3: With end offset = 3 seconds (only first 3 seconds)
        JTLParser.FilterOptions endOffsetOptions = new JTLParser.FilterOptions();
        endOffsetOptions.endOffset = 3;
        Map<String, AggregateResult> endOffsetResults = parser.parse(jtlPath, endOffsetOptions);

        int endOffsetRecords = endOffsetResults.values().stream()
                .mapToInt(AggregateResult::getCount)
                .sum();
        System.out.println("Records with endOffset=3: " + endOffsetRecords);

        assertTrue(endOffsetRecords < totalRecords,
                "End offset should filter out some records");

        // Test 4: With both start=1 and end=3 (records between 1-3 seconds)
        JTLParser.FilterOptions bothOffsetOptions = new JTLParser.FilterOptions();
        bothOffsetOptions.startOffset = 1;
        bothOffsetOptions.endOffset = 3;
        Map<String, AggregateResult> bothOffsetResults = parser.parse(jtlPath, bothOffsetOptions);

        int bothOffsetRecords = bothOffsetResults.values().stream()
                .mapToInt(AggregateResult::getCount)
                .sum();
        System.out.println("Records with startOffset=1 and endOffset=3: " + bothOffsetRecords);

        assertTrue(bothOffsetRecords < totalRecords,
                "Both offsets should filter out records");
        assertTrue(bothOffsetRecords < startOffsetRecords,
                "Both offsets should be more restrictive than start offset alone");
        assertTrue(bothOffsetRecords < endOffsetRecords,
                "Both offsets should be more restrictive than end offset alone");
    }

    @Test
    void testLabelFiltering() throws Exception {
        String jtlPath = "src/main/java/example.jtl";
        JTLParser parser = new JTLParser();

        // Test include labels
        JTLParser.FilterOptions includeOptions = new JTLParser.FilterOptions();
        includeOptions.includeLabels = "HTTP Request1";
        Map<String, AggregateResult> includeResults = parser.parse(jtlPath, includeOptions);

        System.out.println("Labels with include filter: " + includeResults.keySet());
        assertTrue(includeResults.containsKey("HTTP Request1"),
                "Should contain HTTP Request1");
        assertFalse(includeResults.containsKey("HTTP Request2"),
                "Should not contain HTTP Request2");

        // Test exclude labels
        JTLParser.FilterOptions excludeOptions = new JTLParser.FilterOptions();
        excludeOptions.excludeLabels = "Transaction Controller";
        Map<String, AggregateResult> excludeResults = parser.parse(jtlPath, excludeOptions);

        System.out.println("Labels with exclude filter: " + excludeResults.keySet());
        assertFalse(excludeResults.containsKey("Transaction Controller"),
                "Should not contain Transaction Controller");
    }
}
