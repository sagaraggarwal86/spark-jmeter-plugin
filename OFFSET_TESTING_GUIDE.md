# Testing Guide for Start/End Offset Filtering

## Sample JTL File for Testing

Create a test file `sample_test.jtl` with the following data:

```csv
timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect
1000,100,Login,200,OK,Group 1-1,text,true,,1024,512,1,1,http://test.com/login,85,0,15
2000,150,Login,200,OK,Group 1-2,text,true,,1024,512,1,1,http://test.com/login,130,0,20
3000,120,Search,200,OK,Group 1-3,text,true,,2048,1024,1,1,http://test.com/search,100,0,20
15000,200,Login,200,OK,Group 1-1,text,true,,1024,512,1,1,http://test.com/login,180,0,20
20000,180,Search,200,OK,Group 1-2,text,true,,2048,1024,1,1,http://test.com/search,160,0,20
30000,120,Login,200,OK,Group 1-1,text,true,,1024,512,1,1,http://test.com/login,110,0,10
31000,110,Search,200,OK,Group 1-2,text,true,,2048,1024,1,1,http://test.com/search,95,0,15
60000,150,Login,200,OK,Group 1-3,text,true,,1024,512,1,1,http://test.com/login,140,0,10
61000,140,Search,200,OK,Group 1-1,text,true,,2048,1024,1,1,http://test.com/search,125,0,15
90000,130,Login,200,OK,Group 1-2,text,true,,1024,512,1,1,http://test.com/login,120,0,10
91000,135,Search,200,OK,Group 1-3,text,true,,2048,1024,1,1,http://test.com/search,130,0,5
120000,160,Login,500,Error,Group 1-1,text,false,Connection timeout,512,256,1,1,http://test.com/login,150,0,10
121000,155,Search,200,OK,Group 1-2,text,true,,2048,1024,1,1,http://test.com/search,145,0,10
```

### **Data Distribution:**

| Time (sec) | Records | Type | Notes |
|-----------|---------|------|-------|
| 0-5 | 3 | Warm-up | Initial requests, may have high latency |
| 5-30 | 2 | Warm-up | Transition to stable state |
| 30+ | 8 | Stable | Normal operation |

---

## Test Cases

### **Test 1: No Filtering (Baseline)**

**Input:**
```
Start Offset: (empty)
End Offset: (empty)
```

**Expected Results:**
- All 13 records included
- Login avg: (100+150+120+180+110+150+130+160) / 8 = 137.5 ms
- Search avg: (120+180+110+140+135+155) / 6 = 136.67 ms
- Error count: 1 (500 error at 120s)
- Error %: 1/13 = 7.69%

---

### **Test 2: Skip Warm-Up Phase (Start Offset Only)**

**Input:**
```
Start Offset: 30
End Offset: (empty)
```

**Expected Results:**
- Only records at timestamp >= 30000 ms (30 seconds) included
- Records included:
  - timestamp 30000: Login
  - timestamp 31000: Search
  - timestamp 60000: Login
  - timestamp 61000: Search
  - timestamp 90000: Login
  - timestamp 91000: Search
  - timestamp 120000: Login (with error)
  - timestamp 121000: Search
- 8 records total
- Login avg: (120+150+130+160) / 4 = 140 ms
- Search avg: (110+140+135+155) / 4 = 135 ms
- Error count: 1
- Error %: 1/8 = 12.5%

---

### **Test 3: Skip Cool-Down Phase (End Offset Only)**

**Input:**
```
Start Offset: (empty)
End Offset: 90
```

**Expected Results:**
- Only records at timestamp <= 90000 ms (90 seconds) included
- Records included:
  - timestamp 1000: Login
  - timestamp 2000: Login
  - timestamp 3000: Search
  - timestamp 15000: Login
  - timestamp 20000: Search
  - timestamp 30000: Login
  - timestamp 31000: Search
  - timestamp 60000: Login
  - timestamp 61000: Search
  - timestamp 90000: Login
- 10 records total
- Login avg: (100+150+120+180+110+150+130) / 7 = 134.29 ms
- Search avg: (120+180+110+140) / 4 = 137.5 ms
- Error count: 0
- Error %: 0%

---

### **Test 4: Stable Period Only (Both Offsets)**

**Input:**
```
Start Offset: 30
End Offset: 90
```

**Expected Results:**
- Only records between 30-90 seconds included
- Records included:
  - timestamp 30000: Login
  - timestamp 31000: Search
  - timestamp 60000: Login
  - timestamp 61000: Search
  - timestamp 90000: Login
- 5 records total
- Login avg: (120+150+130) / 3 = 133.33 ms
- Search avg: (110+140) / 2 = 125 ms
- Error count: 0
- Error %: 0%

---

### **Test 5: Very Narrow Window**

**Input:**
```
Start Offset: 60
End Offset: 61
```

**Expected Results:**
- Only records between 60-61 seconds
- Records included:
  - timestamp 60000: Login
  - timestamp 61000: Search
- 2 records total
- Login avg: 150 ms
- Search avg: 140 ms
- Error count: 0
- Error %: 0%

---

### **Test 6: Invalid Input Handling**

**Input 1 - Start Offset:**
```
Start Offset: "abc"
End Offset: (empty)
```

**Expected Behavior:**
- Exception caught
- Start offset defaults to 0
- No filtering applied
- All records included

**Input 2 - End Offset:**
```
Start Offset: (empty)
End Offset: "xyz"
```

**Expected Behavior:**
- Exception caught
- End offset defaults to 0
- No filtering applied
- All records included

---

### **Test 7: End Before Start (Edge Case)**

**Input:**
```
Start Offset: 120
End Offset: 30
```

**Expected Results:**
- No records match (end before start)
- 0 records returned
- Table shows "No data available"
- All metrics: 0 or N/A

---

## Testing Procedure

### **Step 1: Setup**
1. Create sample JTL file with provided data
2. Copy to accessible location: `C:\test_data\sample_test.jtl`
3. Open UIPreview or JMeter with plugin

### **Step 2: Run Test Cases**

```
For each test case:
1. Clear all filter fields
2. Enter Start Offset value (if any)
3. Enter End Offset value (if any)
4. Click "Browse..." and select sample_test.jtl
5. Verify results match expected values
6. Document actual vs expected
```

### **Step 3: Verify Results**

```
Check for each metric:
✓ Transaction Count
✓ Average (ms)
✓ Min (ms)
✓ Max (ms)
✓ 90% Line (ms)
✓ Std. Dev
✓ Error %
✓ Throughput
```

---

## Manual Calculation Examples

### **Example: Test 2 Results**

Given filtered records:
```
Login: [120, 150, 130, 160]
Search: [110, 140, 135, 155]
```

**Login Calculations:**
```
Count: 4
Sum: 120 + 150 + 130 + 160 = 560
Average: 560 / 4 = 140 ms
Min: 120 ms
Max: 160 ms
Sorted: [120, 130, 150, 160]
90th Percentile: ceil(0.9 * 4) - 1 = index 3 = 160 ms
Std Dev: sqrt(((120-140)² + (150-140)² + (130-140)² + (160-140)²) / 3)
       = sqrt((400 + 100 + 100 + 400) / 3)
       = sqrt(1000/3) = sqrt(333.33) = 18.26 ms
```

**Search Calculations:**
```
Count: 4
Sum: 110 + 140 + 135 + 155 = 540
Average: 540 / 4 = 135 ms
Min: 110 ms
Max: 155 ms
Sorted: [110, 135, 140, 155]
90th Percentile: ceil(0.9 * 4) - 1 = index 3 = 155 ms
```

---

## Performance Testing

### **Test with Large Dataset**

Generate large JTL file:
```
for i in 1 to 100000:
  timestamp = i * 100 (to distribute over 10M ms = 2.77 hours)
  elapsed = random(50, 500)
  label = "Request" or "API Call" or "Download"
  responseCode = 200 or 500 (random, mostly 200)
  
Add to file
```

**Expected Performance:**
- 100k records: 200-500 ms processing time
- With filters: Same time (filtering is fast)
- Result: Filtered to 30k records (60 sec to 120 sec window)

---

## Integration Testing

### **Test with Real JMeter Results**

1. Run real performance test with JMeter
2. Export results to JTL file
3. Load in plugin
4. Apply various offset combinations
5. Verify filtered metrics match manual calculations
6. Repeat with different test scenarios

---

## Regression Testing

**After any code changes:**

```bash
mvn clean test
```

Ensure all existing tests pass:
- Parse valid JTL file
- Handle invalid input
- Apply label filters
- Apply percentile calculation
- Apply offset filters (NEW)

---

## Known Limitations

1. **Timestamp Format:** Assumes JTL timestamps in milliseconds
2. **Precision:** Offset values are in whole seconds (no fractional seconds)
3. **Performance:** Very large files (>1GB) may be slow
4. **Timezone:** Operates on raw timestamps, no timezone handling

---

## Debugging Tips

### **To verify offset filtering is working:**

1. Add debug logging to `shouldInclude()` method
2. Log every record's timestamp and decision
3. Run with sample data
4. Verify filtered vs non-filtered record counts

### **Sample debug output:**

```
Record 1: timestamp=1000, startOff=30000, endOff=90000 → SKIP (before start)
Record 4: timestamp=15000, startOff=30000, endOff=90000 → SKIP (before start)
Record 6: timestamp=30000, startOff=30000, endOff=90000 → INCLUDE
Record 10: timestamp=90000, startOff=30000, endOff=90000 → INCLUDE
Record 12: timestamp=120000, startOff=30000, endOff=90000 → SKIP (after end)
```

---

**Test Suite Version:** 1.0  
**Last Updated:** March 3, 2026  
**Status:** Ready for Testing
