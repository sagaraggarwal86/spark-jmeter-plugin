# Throughput and Error % Calculation Fix

## Problem Identified

### Previous Incorrect Throughput Calculation
```java
// OLD (WRONG)
double totalSeconds = totalTime / 1000.0;  // Sum of all elapsed times
return count / totalSeconds;
```

**Why This Was Wrong:**
- Used **cumulative elapsed time** instead of **actual time span**
- If 10 requests each take 100ms but run concurrently, the old formula would calculate:
  - `totalTime = 1000ms` (10 × 100ms)
  - `throughput = 10 / 1.0 = 10 req/sec`
- But if those 10 requests all happened in the first second, actual throughput should be **10 req/sec**, not based on cumulative time

**Example of the Problem:**

Given these records:
```
Request 1: timestamp=1000ms, elapsed=500ms → ends at 1500ms
Request 2: timestamp=1200ms, elapsed=300ms → ends at 1500ms
Request 3: timestamp=1400ms, elapsed=100ms → ends at 1500ms
```

- **Old calculation**: `totalTime = 900ms`, `throughput = 3 / 0.9 = 3.33 req/sec` ❌
- **Correct calculation**: Actual span = 1000ms to 1500ms = 500ms, `throughput = 3 / 0.5 = 6 req/sec` ✅

---

## The Fix

### New Correct Throughput Calculation

```java
// Track timestamp range
private long minTimestamp = Long.MAX_VALUE;
private long maxTimestamp = Long.MIN_VALUE;
private long maxElapsedAtMaxTimestamp = 0;

public void addSample(JTLRecord record) {
    long timestamp = record.getTimeStamp();
    long elapsed = record.getElapsed();

    // Track the actual time span of the test
    if (timestamp < minTimestamp) minTimestamp = timestamp;
    if (timestamp > maxTimestamp) {
        maxTimestamp = timestamp;
        maxElapsedAtMaxTimestamp = elapsed;  // Track elapsed time of last request
    }
}

public double getThroughput() {
    // Calculate actual time span: from first request start to last request end
    long timeSpanMs = maxTimestamp - minTimestamp + maxElapsedAtMaxTimestamp;
    double timeSpanSeconds = timeSpanMs / 1000.0;
    return count / timeSpanSeconds;
}
```

### Visual Explanation

```
Time axis (milliseconds):
|-------|-------|-------|-------|-------|
0      1000    2000    3000    4000    5000

Request 1: [========]           (starts at 1000, duration 500ms)
Request 2:    [====]             (starts at 1200, duration 300ms)
Request 3:        [==]           (starts at 1400, duration 100ms)
Request 4:              [====]   (starts at 2500, duration 400ms)
Request 5:                  [==] (starts at 3000, duration 200ms)

minTimestamp = 1000ms (Request 1 start)
maxTimestamp = 3000ms (Request 5 start)
maxElapsedAtMaxTimestamp = 200ms (Request 5 duration)

Actual time span = 3000 - 1000 + 200 = 2200ms = 2.2 seconds

Throughput = 5 requests / 2.2 seconds = 2.27 req/sec ✅

OLD (WRONG) calculation:
totalTime = 500 + 300 + 100 + 400 + 200 = 1500ms = 1.5 seconds
Throughput = 5 / 1.5 = 3.33 req/sec ❌ (INCORRECT!)
```

---

## Why This Matters for Offset Filtering

When you apply offset filters (e.g., startOffset=1, endOffset=5):

### Example Scenario

Full test data:
```
Time 0-1s:   20 requests  (filtered OUT by startOffset=1)
Time 1-5s:   100 requests (INCLUDED)
Time 5-10s:  30 requests  (filtered OUT by endOffset=5)
```

**With OLD calculation:**
- Would calculate throughput based on sum of 100 request durations
- Could give: `100 / (sum of elapsed times in seconds)` = wildly incorrect

**With NEW calculation:**
- Calculates based on actual time span: 1s to 5s = 4 seconds
- Throughput = `100 / 4 = 25 req/sec` ✅
- This correctly represents the throughput during the filtered time window

---

## Error Percentage

### Current Calculation (CORRECT)
```java
public double getErrorPercentage() {
    return count > 0 ? (errorCount * 100.0 / count) : 0;
}
```

This is already correct:
- `errorCount` = number of failed requests
- `count` = total number of requests
- `errorPercentage` = (failed / total) × 100

### How It Works with Filtering

When offset filtering is applied:
- Only filtered records are counted in `count` and `errorCount`
- If 100 requests are filtered, and 5 failed:
  - `errorPercentage = (5 / 100) × 100 = 5%` ✅

**Note:** The error percentage was already correct. The main fix was for throughput calculation.

---

## Edge Cases Handled

### 1. Single Request
```java
if (timeSpanMs <= 0) {
    // Use total elapsed time as fallback
    timeSpanMs = totalTime;
}
```
If only one request, time span would be `0 + elapsed`, which works correctly.

### 2. All Requests at Same Timestamp
If multiple concurrent requests start at the exact same millisecond:
- `maxTimestamp - minTimestamp = 0`
- Add `maxElapsedAtMaxTimestamp` to get the duration of the last request
- Fallback to `totalTime` if still zero

### 3. No Requests (Count = 0)
```java
if (count == 0) return 0;
```
Returns 0 immediately if no samples.

### 4. Uninitialized Timestamps
```java
if (minTimestamp == Long.MAX_VALUE || maxTimestamp == Long.MIN_VALUE) return 0;
```
Safety check for edge cases.

---

## Testing the Fix

### Test Case 1: Sequential Requests
```
Request at 1000ms, duration 100ms
Request at 2000ms, duration 100ms
Request at 3000ms, duration 100ms

Time span = 3000 - 1000 + 100 = 2100ms = 2.1s
Throughput = 3 / 2.1 = 1.43 req/sec ✅
```

### Test Case 2: Concurrent Requests
```
Request at 1000ms, duration 500ms
Request at 1100ms, duration 500ms
Request at 1200ms, duration 500ms

Time span = 1200 - 1000 + 500 = 700ms = 0.7s
Throughput = 3 / 0.7 = 4.29 req/sec ✅
```

### Test Case 3: With Offset Filtering
```
Full data: 0-10 seconds, 100 requests
Filter: startOffset=2, endOffset=6

Filtered: 2-6 seconds, 40 requests
First filtered timestamp: 2000ms
Last filtered timestamp: 5900ms + 50ms elapsed = 5950ms

Time span = 5950 - 2000 = 3950ms = 3.95s
Throughput = 40 / 3.95 = 10.13 req/sec ✅
```

---

## Summary of Changes

### File: `AggregateResult.java`

**Added Fields:**
```java
private long minTimestamp = Long.MAX_VALUE;
private long maxTimestamp = Long.MIN_VALUE;
private long maxElapsedAtMaxTimestamp = 0;
```

**Updated Method: `addSample()`**
- Now tracks min/max timestamps
- Stores elapsed time of the last request

**Updated Method: `getThroughput()`**
- Changed from cumulative time to actual time span
- Formula: `count / ((maxTimestamp - minTimestamp + maxElapsedAtMaxTimestamp) / 1000)`
- Handles edge cases appropriately

**Method: `getErrorPercentage()`**
- No changes needed - already correct

---

## Impact on UI Display

The table will now show:
- **Throughput**: Actual requests per second over the filtered time window
- **Error %**: Percentage of failed requests in the filtered set (already correct)

Both metrics now accurately reflect the filtered data when offset values are applied.
