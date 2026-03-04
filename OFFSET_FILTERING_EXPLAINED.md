# How Offset Filtering Works in UI Preview - Step by Step

## Overview
This document explains how the offset filtering feature works in the Advanced Aggregate Report plugin, from user input to table display.

---

## The Complete Flow

### **Step 1: User Opens UI Preview**
```bash
# Run the UI Preview
mvn test -Dtest=UIPreview
```
- Window opens with empty table
- Filter fields are visible: Start offset, End offset, Include labels, Exclude labels, etc.

---

### **Step 2: User Loads a JTL File**
**Action:** User clicks "Browse..." button and selects `example.jtl`

**Code Path:** `UIPreview.java:124-144`
```java
browseBtn.addActionListener(e -> {
    JFileChooser fc = new JFileChooser();
    if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        File f = fc.getSelectedFile();
        fileNameField.setText(f.getAbsolutePath());
        loadJTLFile(f.getAbsolutePath()); // ← Triggers loading
    }
});
```

---

### **Step 3: Load and Parse JTL File**
**Code Path:** `UIPreview.java:294-326`

#### 3.1: Store File Path
```java
lastLoadedFilePath = filePath; // Save for reloading with new filters
```

#### 3.2: Build Filter Options from UI
**Code Path:** `UIPreview.java:363-398`
```java
JTLParser.FilterOptions options = buildFilterOptions();
// Reads current values from UI fields:
options.startOffset = Integer.parseInt(startOffsetField.getText()); // e.g., 1
options.endOffset = Integer.parseInt(endOffsetField.getText());     // e.g., 5
options.includeLabels = includeLabelsField.getText();
options.excludeLabels = excludeLabelsField.getText();
options.regExp = regExpBox.isSelected();
options.percentile = Integer.parseInt(percentileField.getText());
```

#### 3.3: Call Parser
```java
JTLParser parser = new JTLParser();
Map<String, AggregateResult> results = parser.parse(filePath, options);
```

---

### **Step 4: JTL Parser - First Pass (Find Test Start Time)**
**Code Path:** `JTLParser.java:24-50`

**Why?** Timestamps in JTL are absolute epoch milliseconds (e.g., `1772589381526`). We need to find the earliest timestamp to calculate relative time.

```java
long minTimestamp = Long.MAX_VALUE;

// First pass: scan all records to find minimum timestamp
try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
    String headerLine = reader.readLine(); // Skip header
    String line;
    while ((line = reader.readLine()) != null) {
        JTLRecord record = parseLine(line, columnMap);
        if (record != null) {
            long timestamp = record.getTimeStamp();
            if (timestamp > 0 && timestamp < minTimestamp) {
                minTimestamp = timestamp; // Track earliest timestamp
            }
        }
    }
}

// Store the test start time
options.minTimestamp = minTimestamp; // e.g., 1772589381526
```

**Example from example.jtl:**
```
Line 2: 1772589381534  ←
Line 3: 1772589381526  ← Earliest (minTimestamp)
Line 4: 1772589381753
...
```

---

### **Step 5: JTL Parser - Second Pass (Filter and Aggregate)**
**Code Path:** `JTLParser.java:52-74`

```java
try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
    String headerLine = reader.readLine(); // Skip header
    String line;

    while ((line = reader.readLine()) != null) {
        JTLRecord record = parseLine(line, columnMap);

        // Apply filter - this is where offset filtering happens!
        if (record != null && shouldInclude(record, options)) {
            String label = record.getLabel();
            AggregateResult result = results.computeIfAbsent(label, k -> {
                AggregateResult r = new AggregateResult();
                r.setLabel(label);
                return r;
            });
            result.addSample(record); // Add to aggregated stats
        }
    }
}
```

---

### **Step 6: shouldInclude() - The Filtering Logic**
**Code Path:** `JTLParser.java:128-182`

This is where the magic happens! Each record is checked against all filters.

#### 6.1: Label Filtering (Include/Exclude)
```java
String label = record.getLabel(); // e.g., "HTTP Request1"

// Include filter
if (options.includeLabels != null && !options.includeLabels.isEmpty()) {
    if (options.regExp) {
        if (!label.matches(options.includeLabels)) return false;
    } else {
        if (!label.contains(options.includeLabels)) return false;
    }
}

// Exclude filter
if (options.excludeLabels != null && !options.excludeLabels.isEmpty()) {
    if (options.regExp) {
        if (label.matches(options.excludeLabels)) return false;
    } else {
        if (label.contains(options.excludeLabels)) return false;
    }
}
```

#### 6.2: **Offset Filtering (The Key Part!)**
```java
if (options.startOffset > 0 || options.endOffset > 0) {
    long timestampMs = record.getTimeStamp(); // Absolute timestamp

    // Calculate relative time from test start (in milliseconds)
    long relativeTimeMs = timestampMs - options.minTimestamp;

    // Convert to seconds
    long relativeTimeSec = relativeTimeMs / 1000L;

    // Check start offset
    if (options.startOffset > 0) {
        if (relativeTimeSec < options.startOffset) {
            return false; // Record is too early
        }
    }

    // Check end offset
    if (options.endOffset > 0) {
        if (relativeTimeSec > options.endOffset) {
            return false; // Record is too late
        }
    }
}

return true; // Record passed all filters!
```

**Example Calculation:**

Given:
- `minTimestamp = 1772589381526` (test start)
- `startOffset = 1` (seconds)
- `endOffset = 5` (seconds)

For a record with timestamp `1772589383526`:
```
relativeTimeMs = 1772589383526 - 1772589381526 = 2000 ms
relativeTimeSec = 2000 / 1000 = 2 seconds

Check startOffset: 2 < 1? NO  → Pass
Check endOffset: 2 > 5? NO    → Pass
Result: ✅ INCLUDE THIS RECORD
```

For a record with timestamp `1772589381726`:
```
relativeTimeMs = 1772589381726 - 1772589381526 = 200 ms
relativeTimeSec = 200 / 1000 = 0 seconds

Check startOffset: 0 < 1? YES → FAIL
Result: ❌ EXCLUDE THIS RECORD
```

For a record with timestamp `1772589387526`:
```
relativeTimeMs = 1772589387526 - 1772589381526 = 6000 ms
relativeTimeSec = 6000 / 1000 = 6 seconds

Check endOffset: 6 > 5? YES → FAIL
Result: ❌ EXCLUDE THIS RECORD
```

---

### **Step 7: Aggregate Filtered Records**
**Code Path:** `AggregateResult.java:20-34`

Only records that pass the filter are added to the aggregate:

```java
public void addSample(JTLRecord record) {
    count++;                          // Increment sample count
    long elapsed = record.getElapsed();
    totalTime += elapsed;             // Sum for average calculation
    times.add(elapsed);               // Store for percentile & stddev

    if (elapsed < minTime) minTime = elapsed;
    if (elapsed > maxTime) maxTime = elapsed;

    if (!record.isSuccess()) {
        errorCount++;
    }

    totalBytes += record.getBytes();
}
```

**Key Point:** The aggregated statistics (count, average, min, max, percentile, etc.) are calculated ONLY from the filtered records, not all records!

---

### **Step 8: Populate UI Table**
**Code Path:** `UIPreview.java:243-267`

```java
for (AggregateResult result : results.values()) {
    Object[] row = new Object[]{
        result.getLabel(),                          // "HTTP Request1"
        result.getCount(),                          // 50 (filtered count!)
        df0.format(result.getAverage()),            // 75 ms
        result.getMin(),                            // 60 ms
        result.getMax(),                            // 120 ms
        df0.format(result.getPercentile(percentile)), // 95 ms
        df1.format(result.getStdDev()),             // 12.5 ms
        df2.format(result.getErrorPercentage()) + "%",  // 0%
        df1.format(result.getThroughput()) + "/sec" // 8.3/sec
    };
    tableModel.addRow(row);
}
```

**Table displays:**
- Transaction Name: From filtered labels
- Transaction Count: Only records that passed filters
- Average: Calculated from filtered records only
- Min/Max: From filtered records only
- 90% Line: Percentile from filtered records only
- Std. Dev.: Standard deviation from filtered records only
- Error %: Error percentage from filtered records only
- Throughput: Requests per second from filtered records only

---

### **Step 9: User Changes Offset Values (Real-Time Update)**
**New Feature!** Now the table updates automatically when offset values change.

**Code Path:** `UIPreview.java:92-112`

```java
// Setup listeners for offset fields to reload data when changed
javax.swing.event.DocumentListener offsetListener = new javax.swing.event.DocumentListener() {
    public void changedUpdate(javax.swing.event.DocumentEvent e) {
        reloadWithCurrentFilters();
    }
    public void removeUpdate(javax.swing.event.DocumentEvent e) {
        reloadWithCurrentFilters();
    }
    public void insertUpdate(javax.swing.event.DocumentEvent e) {
        reloadWithCurrentFilters();
    }
};
startOffsetField.getDocument().addDocumentListener(offsetListener);
endOffsetField.getDocument().addDocumentListener(offsetListener);
```

**Sequence:**
1. User types "2" in Start Offset field
2. Document listener fires → calls `reloadWithCurrentFilters()`
3. Re-parses JTL file with new filter values
4. Table updates with new filtered results

**Code Path:** `UIPreview.java:331-358`
```java
private void reloadWithCurrentFilters() {
    if (lastLoadedFilePath == null || lastLoadedFilePath.isEmpty()) {
        return; // No file loaded yet
    }

    // Re-parse with current filter values
    JTLParser parser = new JTLParser();
    JTLParser.FilterOptions options = buildFilterOptions(); // Reads current UI values
    Map<String, AggregateResult> results = parser.parse(lastLoadedFilePath, options);

    // Update table
    populateTableWithResults(results, options.percentile);
}
```

---

## Summary

### What Happens When You Set `startOffset=1` and `endOffset=5`:

1. ✅ JTL file is parsed twice:
   - **First pass**: Find minimum timestamp (test start time)
   - **Second pass**: Filter and aggregate records

2. ✅ For each record:
   - Calculate: `relativeTime = (recordTimestamp - testStartTime) / 1000` seconds
   - Include only if: `1 <= relativeTime <= 5`

3. ✅ Table displays:
   - Only transactions that occurred between 1-5 seconds from test start
   - All metrics (count, average, min, max, percentile, etc.) calculated from filtered records

4. ✅ Real-time updates:
   - Change offset values → table automatically updates
   - No need to reload the file manually

---

## Testing the Feature

### Manual Test Steps:
1. Run `UIPreview.main()`
2. Click "Browse..." and select `example.jtl`
3. Observe initial results (all records)
4. Enter "1" in Start Offset field
5. **Watch table update automatically** - rows decrease
6. Enter "3" in End Offset field
7. **Watch table update automatically** - rows decrease further
8. Clear offset fields
9. **Watch table restore** to show all records

### Expected Behavior:
- **No offsets**: All records displayed
- **Start offset = 1**: Records from 0-1 seconds excluded
- **End offset = 3**: Records after 3 seconds excluded
- **Both**: Only records between 1-3 seconds displayed

---

## Files Modified

1. **JTLParser.java**
   - Added two-pass parsing to find test start time
   - Fixed offset filtering logic to use relative time
   - Added `minTimestamp` field to FilterOptions

2. **UIPreview.java**
   - Added document listeners for offset fields
   - Added `reloadWithCurrentFilters()` method
   - Added `buildFilterOptions()` helper method
   - Added `lastLoadedFilePath` tracking

3. **SamplePluginSamplerUI.java**
   - Same changes as UIPreview.java for JMeter integration

---

## Key Implementation Details

### Why Two Passes?
- JTL files contain absolute timestamps (epoch milliseconds)
- Offsets are relative to test start (seconds from start)
- Must find minimum timestamp first to calculate relative time

### Why Relative Time?
- Users think in terms of "skip first 5 seconds of test"
- Not "skip records before timestamp 1772589386526"
- More intuitive and portable across test runs

### Real-Time Updates
- Provides immediate visual feedback
- No need to reload file after changing filters
- Improves user experience significantly
