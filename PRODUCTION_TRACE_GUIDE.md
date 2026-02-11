# Production Trace Log Interpretation Guide

## Problem
Production API returns only 1 mapping with key "af", but tests show 27 mappings with correct keys.

## Solution
Deploy latest code with trace logging, then analyze the logs to find where production diverges from test behavior.

---

## Expected Test Behavior (Working)

```
[TRACE] suggestMappings called with 50 element files
[TRACE] Column 'Type' -> canonical concept 'type'
[TRACE] Column 'Sex' -> canonical concept 'sex'
[TRACE] Column 'Toileting' -> canonical concept 'toilet'
[TRACE] Column 'ToiletBART1' -> canonical concept 'toilet'
...
[TRACE] suggestWithoutSchema: clustering 50 columns
[TRACE] clusterColumns: input 50 columns
[TRACE] clusterColumns: formed 32 initial concept-based clusters
[TRACE] clusterColumns: merged 5 clusters (threshold=0.56), final count: 27
[TRACE] suggestWithoutSchema: formed 27 clusters
[TRACE] Cluster 1/27: key='bath', representative='bath', 6 cols, 2 picked
[TRACE] Cluster 10/27: key='sex', representative='sex', 2 cols, 2 picked
[TRACE] Cluster 13/27: key='type', representative='type', 2 cols, 2 picked
```

**Result**: 27 mappings with proper keys (bath, sex, type, bowel, bladder, etc.)

---

## Diagnostic Scenarios

### Scenario 1: All Columns Get Same Canonical Concept

**Symptoms:**
```
[TRACE] Column 'Type' -> canonical concept 'af'
[TRACE] Column 'Sex' -> canonical concept 'af'
[TRACE] Column 'Toileting' -> canonical concept 'af'
[TRACE] Column 'ToiletBART1' -> canonical concept 'af'
...
[TRACE] clusterColumns: formed 1 initial concept-based clusters
```

**Diagnosis**: Bug in `canonicalConceptName()` - all columns being assigned same concept

**Likely Cause**: LearnedNoise is incorrectly flagging ALL tokens as noise, leaving only common suffix/prefix

**Fix**: Check token frequency calculation in `learnNoiseFromRequest()`

---

### Scenario 2: Embeddings Too Similar (Everything Merges)

**Symptoms:**
```
[TRACE] clusterColumns: formed 32 initial concept-based clusters
[TRACE] clusterColumns: merged 31 clusters (threshold=0.56), final count: 1
```

**Diagnosis**: Initial clusters formed correctly, but embeddings are so similar that everything merges into one cluster

**Likely Causes**:
1. All embeddings returning zero vectors
2. All embeddings returning identical vectors
3. EmbeddingsClient failing silently and returning default vectors

**Fix**: Check embedding sample logs:
```
[TRACE] embedColumnWithValues('Sex: F, M') -> vector[0..3]=[0.123, -0.456, 0.789, ...], dim=384
```

If vectors are all zeros or all identical, EmbeddingsClient is broken.

---

### Scenario 3: Request Truncated/Filtered

**Symptoms:**
```
[TRACE] suggestMappings called with 2 element files
```

**Diagnosis**: Only 2 files reaching the service instead of 50+

**Likely Causes**:
1. Request body too large, being truncated
2. Controller filtering/validating request
3. Network issue truncating JSON

**Fix**: Check controller logs, request size limits, network configuration

---

### Scenario 4: Schema Mode Activated

**Symptoms:**
```
[TRACE] suggestMappings called with 50 element files
... (no "suggestWithoutSchema" log)
```

**Diagnosis**: Using schema-based mode instead of schemaless mode

**Fix**: Check if `req.getSchema()` is not null. Schema mode has different behavior.

---

## How to Collect Logs

### 1. Deploy Latest Code
```bash
git pull origin copilot/add-llms-integration
mvn clean package -DskipTests
# Deploy the WAR/JAR to your server
```

### 2. Restart Application
Ensure new code is loaded.

### 3. Send Test Request
Use the exact curl command that's failing:
```bash
curl 'https://semantics.inf.um.es/taniwha-ws/api/mappings/suggest' \
  -H 'Content-Type: application/json' \
  --data-raw '{ ... your 85-element request ... }'
```

### 4. Grep Logs for Trace
```bash
# View all trace logs
grep "\[TRACE\]" /path/to/application.log

# Or view specific sections
grep "\[TRACE\] suggestMappings" /path/to/application.log
grep "\[TRACE\] Column" /path/to/application.log | head -20
grep "\[TRACE\] clusterColumns" /path/to/application.log
grep "\[TRACE\] Cluster" /path/to/application.log
```

### 5. Compare with Expected Behavior
Look for differences from the expected test behavior above.

---

## Quick Diagnosis Decision Tree

```
Does production log show: "[TRACE] suggestMappings called with X element files"?
├─ NO → Check if logs are enabled, if controller is being called
└─ YES → How many files? (should be 50+)
    ├─ Few (< 10) → Request truncation issue (Scenario 3)
    └─ Many (50+) → Check canonical concepts
        │
        Does production log show different concepts for different columns?
        ├─ NO (all same) → Canonical concept bug (Scenario 1)
        └─ YES (diverse) → Check initial cluster count
            │
            Does production log show many initial clusters (20+)?
            ├─ NO (few clusters) → Canonical concept not working right
            └─ YES → Check merge count
                │
                Did most clusters merge into one?
                ├─ YES → Embedding similarity issue (Scenario 2)
                └─ NO → Something else - share full trace logs
```

---

## Contact

If logs don't match any scenario above, share:
1. Complete `[TRACE]` logs from production
2. Complete `[EmbeddingsClient]` and `[MappingService]` logs
3. Request JSON being sent
4. Response JSON being returned

This will allow precise diagnosis of the production-specific issue.
