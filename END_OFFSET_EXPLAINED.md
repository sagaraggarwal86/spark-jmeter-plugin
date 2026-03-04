# End Offset (Sec) - Detailed Explanation

## What is End Offset?

**End Offset** is a filter that specifies the **maximum relative time** (in seconds from test start) for including records in the aggregate report.

---

## How End Offset Works - Step by Step

### **Scenario: Understanding End Offset**

Let's say you have a JMeter test that ran for 10 seconds and generated 100 requests.

```
Test Timeline:
|-------|-------|-------|-------|-------|-------|-------|-------|-------|-------|
0s      1s      2s      3s      4s      5s      6s      7s      8s      9s      10s
[==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==]
  req     req     req     req     req     req     req     req     req     req
```

Each second has 10 requests, total = 100 requests.

---

## Calculation Logic

### **Step 1: Find Test Start Time**

The parser reads all records and finds the **minimum timestamp** (earliest request):

```java
// Example timestamps from example.jtl
Line 2: 1772589381534
Line 3: 1772589381526  ← Earliest (minTimestamp = 1772589381526)
Line 4: 1772589381753
...
```

**This is the test start time** (time zero for relative calculations).

---

### **Step 2: Calculate Relative Time for Each Record**

For each record in the JTL file:

```java
long timestampMs = record.getTimeStamp();           // e.g., 1772589385526
long relativeTimeMs = timestampMs - minTimestamp;   // 1772589385526 - 1772589381526 = 4000 ms
long relativeTimeSec = relativeTimeMs / 1000L;      // 4000 / 1000 = 4 seconds
```

**This tells us:** "This request occurred 4 seconds after the test started"

---

### **Step 3: Apply End Offset Filter**

```java
if (options.endOffset > 0) {
    if (relativeTimeSec > options.endOffset) {
        return false;  // EXCLUDE this record
    }
}
```

**Translation:**
- If `endOffset = 5`, only include records where `relativeTimeSec <= 5`
- Records at 5.1 seconds, 6 seconds, 7 seconds, etc. are **EXCLUDED**

---

## Examples with Different End Offset Values

### **Example 1: endOffset = 0 (No Filter)**

```
endOffset = 0  →  No filtering applied
Result: All 100 requests included (0-10 seconds)
```

**Code Logic:**
```java
if (options.endOffset > 0) {  // 0 > 0? NO
    // Skip filtering
}
return true;  // Include all records
```

---

### **Example 2: endOffset = 5**

```
endOffset = 5  →  Include only records from 0-5 seconds

Timeline:
|-------|-------|-------|-------|-------|-------|-------|-------|-------|-------|
0s      1s      2s      3s      4s      5s      6s      7s      8s      9s      10s
[==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==]
   ✅      ✅      ✅      ✅      ✅      ✅      ❌      ❌      ❌      ❌      ❌

Result: 60 requests included (0-5 seconds), 40 excluded (>5 seconds)
```

**Filtering Process:**

| Record Timestamp | Relative Time | endOffset = 5 | Include? |
|------------------|---------------|---------------|----------|
| 1772589381526    | 0.0 sec       | 0 <= 5?  YES  | ✅       |
| 1772589382526    | 1.0 sec       | 1 <= 5?  YES  | ✅       |
| 1772589383526    | 2.0 sec       | 2 <= 5?  YES  | ✅       |
| 1772589384526    | 3.0 sec       | 3 <= 5?  YES  | ✅       |
| 1772589385526    | 4.0 sec       | 4 <= 5?  YES  | ✅       |
| 1772589386526    | 5.0 sec       | 5 <= 5?  YES  | ✅       |
| 1772589387526    | 6.0 sec       | 6 <= 5?  NO   | ❌       |
| 1772589388526    | 7.0 sec       | 7 <= 5?  NO   | ❌       |
| 1772589389526    | 8.0 sec       | 8 <= 5?  NO   | ❌       |
| 1772589390526    | 9.0 sec       | 9 <= 5?  NO   | ❌       |
| 1772589391526    | 10.0 sec      | 10 <= 5? NO   | ❌       |

---

### **Example 3: endOffset = 3**

```
endOffset = 3  →  Include only records from 0-3 seconds

Timeline:
|-------|-------|-------|-------|-------|-------|-------|-------|-------|-------|
0s      1s      2s      3s      4s      5s      6s      7s      8s      9s      10s
[==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==]
   ✅      ✅      ✅      ✅      ❌      ❌      ❌      ❌      ❌      ❌      ❌

Result: 40 requests included (0-3 seconds), 60 excluded (>3 seconds)
```

---

## Combining Start Offset + End Offset

### **Example 4: startOffset = 2, endOffset = 7**

```
startOffset = 2, endOffset = 7  →  Include only records from 2-7 seconds

Timeline:
|-------|-------|-------|-------|-------|-------|-------|-------|-------|-------|
0s      1s      2s      3s      4s      5s      6s      7s      8s      9s      10s
[==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==][==10==]
   ❌      ❌      ✅      ✅      ✅      ✅      ✅      ✅      ❌      ❌      ❌

Result: 60 requests included (2-7 seconds), 40 excluded (0-2s and >7s)
```

**Filtering Logic:**
```java
// For a record at 1.5 seconds:
relativeTimeSec = 1.5

// Check startOffset
if (relativeTimeSec < startOffset) {  // 1.5 < 2? YES
    return false;  // EXCLUDE (too early)
}

// Check endOffset
if (relativeTimeSec > endOffset) {    // Not reached
    return false;
}

return true;
```

```java
// For a record at 5.0 seconds:
relativeTimeSec = 5.0

// Check startOffset
if (relativeTimeSec < startOffset) {  // 5.0 < 2? NO
    // Continue
}

// Check endOffset
if (relativeTimeSec > endOffset) {    // 5.0 > 7? NO
    // Continue
}

return true;  // INCLUDE ✅
```

```java
// For a record at 8.5 seconds:
relativeTimeSec = 8.5

// Check startOffset
if (relativeTimeSec < startOffset) {  // 8.5 < 2? NO
    // Continue
}

// Check endOffset
if (relativeTimeSec > endOffset) {    // 8.5 > 7? YES
    return false;  // EXCLUDE (too late)
}
```

---

## Why Use End Offset?

### **Use Case 1: Ignore Ramp-Down Period**

Many load tests have a ramp-down phase where load decreases. You may want to exclude this period:

```
Test Structure:
0-5s:   Ramp-up (increasing load)
5-20s:  Steady state (full load) ← Analyze this
20-25s: Ramp-down (decreasing load) ← Exclude this

Solution: endOffset = 20
```

### **Use Case 2: Analyze Only First N Seconds**

You want to see how the system performs in the first 5 seconds:

```
Test ran for 60 seconds, but you only care about first 5 seconds

Solution: endOffset = 5
```

### **Use Case 3: Compare Time Windows**

Compare early vs. late performance:

```
Early performance:  startOffset = 0,  endOffset = 30
Late performance:   startOffset = 30, endOffset = 60
```

---

## Impact on Aggregate Metrics

When you set `endOffset = 5`, **ALL metrics are calculated from filtered records only**:

### **Before Filtering (All 10 seconds)**
```
Total Requests: 100
Average Response Time: 85ms
Throughput: 10 req/sec
Error %: 5%
```

### **After Filtering (endOffset = 5, only 0-5 seconds)**
```
Total Requests: 60 (only 0-5s records)
Average Response Time: 72ms (average of filtered records only)
Throughput: 12 req/sec (60 requests / 5 seconds)
Error %: 3% (errors in 0-5s range only)
```

**The filtered results show what happened during the 0-5 second window, not the entire test.**

---

## Code Reference

### **File: `JTLParser.java:158-179`**

```java
// Apply timestamp filters (offset in seconds relative to test start)
if (options.startOffset > 0 || options.endOffset > 0) {
    long timestampMs = record.getTimeStamp();

    // Calculate relative time from the start of the test (in milliseconds)
    long relativeTimeMs = timestampMs - options.minTimestamp;
    long relativeTimeSec = relativeTimeMs / 1000L;

    // If start offset is set, filter out records before the start time
    if (options.startOffset > 0) {
        if (relativeTimeSec < options.startOffset) {
            return false;  // Record too early
        }
    }

    // If end offset is set, filter out records after the end time
    if (options.endOffset > 0) {
        if (relativeTimeSec > options.endOffset) {
            return false;  // Record too late ← END OFFSET CHECK
        }
    }
}

return true;  // Record passed all filters
```

---

## Important Notes

### **1. Boundary Behavior**

```java
if (relativeTimeSec > options.endOffset) {
    return false;
}
```

- **Inclusive**: Records **at exactly** endOffset seconds are INCLUDED
- **Exclusive**: Records **after** endOffset seconds are EXCLUDED

Example with `endOffset = 5`:
- Record at 5.0 seconds: ✅ INCLUDED
- Record at 5.001 seconds: ❌ EXCLUDED

### **2. Millisecond Precision**

Even though you specify seconds, the calculation uses millisecond precision:

```java
long relativeTimeMs = timestampMs - options.minTimestamp;  // Precise to millisecond
long relativeTimeSec = relativeTimeMs / 1000L;             // Convert to seconds (truncated)
```

### **3. No End Offset = No Filter**

If you leave End Offset empty or set to 0:
```java
if (options.endOffset > 0) {  // 0 > 0? NO
    // Skip filtering
}
```
All records are included, regardless of time.

---

## Real Example from example.jtl

Let's apply `endOffset = 5` to actual data:

```
First record timestamp: 1772589381526 (minTimestamp)

Record at 1772589381526: relative = 0.000s     → ✅ INCLUDE (0 <= 5)
Record at 1772589382526: relative = 1.000s     → ✅ INCLUDE (1 <= 5)
Record at 1772589383526: relative = 2.000s     → ✅ INCLUDE (2 <= 5)
Record at 1772589384526: relative = 3.000s     → ✅ INCLUDE (3 <= 5)
Record at 1772589385526: relative = 4.000s     → ✅ INCLUDE (4 <= 5)
Record at 1772589386526: relative = 5.000s     → ✅ INCLUDE (5 <= 5)
Record at 1772589386600: relative = 5.074s     → ❌ EXCLUDE (5.074 > 5)
Record at 1772589387526: relative = 6.000s     → ❌ EXCLUDE (6 > 5)
Record at 1772589388526: relative = 7.000s     → ❌ EXCLUDE (7 > 5)
...
Record at 1772589391526: relative = 10.000s    → ❌ EXCLUDE (10 > 5)
```

---

## Summary

**End Offset** = "Stop analyzing data after N seconds from test start"

| Setting | Meaning |
|---------|---------|
| `endOffset = 0` | No limit - analyze entire test |
| `endOffset = 5` | Analyze only first 5 seconds |
| `endOffset = 30` | Analyze only first 30 seconds |
| `startOffset = 5, endOffset = 10` | Analyze only 5-10 second window |

**Key Point:** End Offset is **relative to test start**, not absolute time. It helps you focus analysis on specific time windows of your load test.
