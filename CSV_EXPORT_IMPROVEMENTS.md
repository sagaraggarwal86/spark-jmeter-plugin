# CSV Export Improvements - Error % Formatting and Exact UI Match

## Changes Made

### **1. Fixed Error % Formatting - Always Show 2 Decimal Places**

#### **Problem**
Error percentage was displayed with variable decimal places:
- `5%` instead of `5.00%`
- `0.5%` instead of `0.50%`
- `0.05%` was shown correctly but inconsistently

#### **Solution**
Changed `DecimalFormat` from `#.##` to `0.00`:

**Before:**
```java
DecimalFormat df2 = new DecimalFormat("#.##");  // Variable decimals
```

**After:**
```java
DecimalFormat df2 = new DecimalFormat("0.00");  // Always 2 decimals
```

#### **Examples**

| Error Rate | Before (Wrong) | After (Correct) |
|------------|----------------|-----------------|
| 0%         | `0%`           | `0.00%`         |
| 0.05%      | `0.05%`        | `0.05%`         |
| 0.5%       | `0.5%`         | `0.50%`         |
| 5%         | `5%`           | `5.00%`         |
| 12.34%     | `12.34%`       | `12.34%`        |
| 100%       | `100%`         | `100.00%`       |

---

### **2. CSV Export Now Matches UI Exactly**

#### **Problem**
CSV export was trying to add extra columns that weren't in the UI:
- `Received KB/sec` (not in UI)
- `Sent KB/sec` (not in UI)
- `Avg. Bytes` (not in UI)
- Different formatting than displayed

#### **Solution**
Completely rewrote CSV export to iterate through the table model directly, saving exactly what's displayed.

**New Approach:**
```java
// Get headers from table
for (int col = 0; col < tableModel.getColumnCount(); col++) {
    header.append(tableModel.getColumnName(col));
}

// Get data from table (exactly as displayed)
for (int row = 0; row < tableModel.getRowCount(); row++) {
    for (int col = 0; col < tableModel.getColumnCount(); col++) {
        Object value = tableModel.getValueAt(row, col);
        String cellValue = value != null ? value.toString() : "";
        line.append(cellValue);
    }
}
```

---

## CSV Output Format

### **Header (when "Save Table Header" is checked)**
```csv
Transaction Name,Transaction Count,Average,Min,Max,90% Line,Std. Dev.,Error %,Throughput
```

**Note:** If percentile is changed to 95, header becomes:
```csv
Transaction Name,Transaction Count,Average,Min,Max,95% Line,Std. Dev.,Error %,Throughput
```

### **Data Rows - Exact UI Format**
```csv
HTTP Request1,100,75,60,120,95,12.5,0.00%,25.0/sec
HTTP Request2,150,80,65,110,100,10.2,2.50%,30.0/sec
Transaction Controller,250,77,60,120,98,11.8,1.20%,27.5/sec
```

**Key Points:**
- ✅ Error % includes the `%` symbol: `0.00%`, `2.50%`
- ✅ Throughput includes `/sec`: `25.0/sec`
- ✅ Std. Dev. has 1 decimal: `12.5`
- ✅ Average has no decimals: `75` (integer)
- ✅ Percentile column name matches UI (e.g., "90% Line" or "95% Line")

---

## Comparison: Before vs After

### **Before (Wrong)**

**UI Display:**
```
Transaction Name  | Error %
HTTP Request1     | 0.05%
HTTP Request2     | 5%
```

**CSV Export:**
```csv
Label,# Samples,Average,Min,Max,90th pct,Std. Dev.,Error %,Throughput,Received KB/sec,Sent KB/sec,Avg. Bytes
HTTP Request1,100,75,60,120,95,12.5,0.05,25.0,0.0,0.0,19500
HTTP Request2,150,80,65,110,100,10.2,5,30.0,0.0,0.0,19300
```

**Problems:**
- ❌ Error % formatted inconsistently: `5%` in UI, but saved as `5` in CSV
- ❌ Throughput missing `/sec`: `25.0/sec` in UI, but saved as `25.0`
- ❌ Extra columns not in UI: `Received KB/sec`, `Sent KB/sec`, `Avg. Bytes`
- ❌ Column header mismatch: UI says "Transaction Name", CSV says "Label"

---

### **After (Correct)**

**UI Display:**
```
Transaction Name  | Error %
HTTP Request1     | 0.05%
HTTP Request2     | 5.00%
```

**CSV Export:**
```csv
Transaction Name,Transaction Count,Average,Min,Max,90% Line,Std. Dev.,Error %,Throughput
HTTP Request1,100,75,60,120,95,12.5,0.05%,25.0/sec
HTTP Request2,150,80,65,110,100,10.2,5.00%,30.0/sec
```

**Fixed:**
- ✅ Error % always has 2 decimals in UI: `5.00%`
- ✅ CSV matches UI exactly: `5.00%` (with % symbol)
- ✅ Throughput includes `/sec`: `25.0/sec`
- ✅ No extra columns - only what's visible in UI
- ✅ Column headers match UI exactly

---

## Technical Details

### **Files Modified**

1. **UIPreview.java**
   - Line 274: Changed `DecimalFormat df2 = new DecimalFormat("0.00")`
   - Lines 451-487: Rewrote `saveTableToCSV()` to iterate table model

2. **SamplePluginSamplerUI.java**
   - Line 130: Changed `DecimalFormat df2 = new DecimalFormat("0.00")`
   - Lines 497-533: Rewrote `saveTableToCSV()` to iterate table model

### **Benefits**

1. **Consistency**: UI and CSV are always in sync
2. **Simplicity**: No need to maintain two separate formatting logic
3. **Flexibility**: If UI columns change, CSV automatically adapts
4. **Correctness**: Error % always shows 2 decimal places

### **CSV Escaping**

Labels (Transaction Names) are properly escaped for CSV:
- Commas → Wrapped in quotes
- Quotes → Doubled
- Newlines → Wrapped in quotes

```java
private String escapeCSV(String value) {
    if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    return value;
}
```

**Example:**
```
Label with, comma  →  "Label with, comma"
Label with "quote" →  "Label with ""quote"""
```

---

## Testing

### **Test Case 1: Error % Formatting**

**Setup:**
- Load JTL file with various error rates
- Check UI display

**Expected Result:**
```
0 errors → 0.00%
1 error out of 200 → 0.50%
5 errors out of 100 → 5.00%
All failed → 100.00%
```

### **Test Case 2: CSV Export Matches UI**

**Setup:**
1. Load JTL file
2. Apply filters (e.g., startOffset=2, endOffset=8)
3. Change percentile to 95
4. Click "Save Table Data"

**Expected CSV:**
```csv
Transaction Name,Transaction Count,Average,Min,Max,95% Line,Std. Dev.,Error %,Throughput
HTTP Request1,50,78,65,110,105,10.5,0.00%,12.5/sec
```

**Verify:**
- ✅ Header says "95% Line" (not "90% Line")
- ✅ Data includes filtered count (50, not 100)
- ✅ Error % has 2 decimals: `0.00%`
- ✅ Throughput has `/sec`: `12.5/sec`
- ✅ No extra columns

### **Test Case 3: Save Table Header Checkbox**

**Setup:**
1. Uncheck "Save Table Header"
2. Save CSV

**Expected Result:**
```csv
HTTP Request1,100,75,60,120,95,12.5,0.00%,25.0/sec
HTTP Request2,150,80,65,110,100,10.2,5.00%,30.0/sec
```
(No header row)

---

## Summary

### **What Changed**

1. ✅ Error % always displays with 2 decimal places (e.g., `0.05%`, `5.00%`)
2. ✅ CSV export saves exactly what's shown in the UI
3. ✅ No extra columns in CSV that aren't visible in UI
4. ✅ Column headers in CSV match UI headers exactly
5. ✅ All formatting (%, /sec, decimals) preserved in CSV

### **Build Status**

✅ **Build successful** - All changes compile without errors.

The CSV export now provides a true representation of what users see in the UI, maintaining consistency and eliminating confusion.
