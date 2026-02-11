# Next Steps - Production Debugging

## Current Situation

### What's Working ✅
- **Embeddings initialize successfully** in production (dimension: 384)
- **MappingService has EmbeddingsClient** present
- **Tests pass perfectly**: 27 mappings with correct keys (bath, sex, type, bowel, etc.)

### What's Failing ❌
- **Production API returns**: Only 1 mapping with key "af" containing all Sex values
- **Expected**: 40+ mappings with semantic keys

### Root Cause
The embeddings initialize fine, but **something happens during the actual mapping call** that breaks the clustering logic. Tests prove the code works, so this is a production-specific runtime issue.

---

## What I've Added

### 1. Comprehensive Trace Logging
The code now logs every step of the mapping process:
- How many element files received
- Canonical concept assignment for each column
- Initial cluster formation
- Fuzzy merge operations
- Final cluster count and representatives

### 2. Diagnostic Guide
`PRODUCTION_TRACE_GUIDE.md` contains:
- Expected behavior (from tests)
- 4 diagnostic scenarios with symptoms
- Decision tree for quick diagnosis
- Log collection instructions

---

## What You Need to Do

### Step 1: Deploy Latest Code
```bash
# Pull latest changes
git checkout copilot/add-llms-integration
git pull

# Build
mvn clean package -DskipTests

# Deploy to your server
# (copy target/*.war or target/*.jar to your deployment location)

# Restart the application
```

### Step 2: Send the Problematic Request
```bash
# Use the exact same curl command that's failing
curl 'https://semantics.inf.um.es/taniwha-ws/api/mappings/suggest' \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer ...' \
  --data-raw '{ ... your 85-element request ... }'
```

### Step 3: Collect the Trace Logs
```bash
# Option 1: View all trace logs
grep "\[TRACE\]" /path/to/your/application.log

# Option 2: View specific sections
grep "\[TRACE\] suggestMappings" /path/to/application.log
grep "\[TRACE\] Column" /path/to/application.log | head -20
grep "\[TRACE\] clusterColumns" /path/to/application.log
grep "\[TRACE\] Cluster" /path/to/application.log | head -30

# Option 3: Save to file for analysis
grep "\[TRACE\]" /path/to/application.log > trace_output.txt
```

### Step 4: Analyze the Logs
Compare your production logs with the expected test behavior in `PRODUCTION_TRACE_GUIDE.md`.

**Expected (from tests):**
```
[TRACE] suggestMappings called with 50 element files
[TRACE] Column 'Type' -> canonical concept 'type'
[TRACE] Column 'Sex' -> canonical concept 'sex'
[TRACE] clusterColumns: formed 32 initial concept-based clusters
[TRACE] clusterColumns: merged 5 clusters, final count: 27
```

**Look for:**
- Are all columns being assigned the same canonical concept? (Scenario 1)
- Are there many initial clusters that all merge into one? (Scenario 2)
- Is the request being truncated? (Scenario 3)
- Is something else unexpected happening?

### Step 5: Share the Results
Once you have the trace logs, one of these will be true:

**Scenario A: Issue is obvious from logs**
- Follow the fix in `PRODUCTION_TRACE_GUIDE.md` for your scenario
- I can help implement the fix

**Scenario B: Issue is not clear**
- Share the trace logs here
- I'll analyze and provide a targeted fix

---

## Most Likely Scenarios

Based on the symptoms, here are my top hypotheses (in order):

### 1. Canonical Concept Bug (70% probability)
All columns are being assigned the same canonical concept (e.g., "af"), causing them to cluster together.

**What to look for:**
```
[TRACE] Column 'Type' -> canonical concept 'af'
[TRACE] Column 'Sex' -> canonical concept 'af'
[TRACE] Column 'Toileting' -> canonical concept 'af'
```

**Why it might happen:**
The noise learning algorithm might be flagging all tokens as noise in production, leaving only a common suffix.

### 2. Embedding Quality Issue (20% probability)
Embeddings are all identical or too similar, causing everything to merge.

**What to look for:**
```
[TRACE] clusterColumns: formed 32 initial clusters
[TRACE] clusterColumns: merged 31 clusters, final count: 1
```

**Why it might happen:**
EmbeddingsClient might be returning zero vectors or default vectors instead of real embeddings.

### 3. Request Processing Issue (10% probability)
Request is being modified/filtered before reaching the service.

**What to look for:**
```
[TRACE] suggestMappings called with 2 element files
```
(Should be 85 files, not 2)

---

## Quick Reference

### Files to Check
- `PRODUCTION_TRACE_GUIDE.md` - Comprehensive diagnostic guide
- `PRODUCTION_DEBUGGING_GUIDE.md` - Environment troubleshooting
- Test output showing expected behavior

### Log Patterns to Grep
```bash
# Start of mapping call
grep "\[TRACE\] suggestMappings called" app.log

# Canonical concepts (first 10)
grep "\[TRACE\] Column" app.log

# Clustering summary
grep "\[TRACE\] clusterColumns:" app.log

# Individual clusters
grep "\[TRACE\] Cluster" app.log
```

### Decision Tree
```
Logs show "[TRACE] suggestMappings called"?
├─ NO → Check if app restarted, if endpoint being hit
└─ YES → Check element count
    ├─ Low (< 10) → Request truncation
    └─ High (50+) → Check canonical concepts
        ├─ All same → Canonical concept bug
        └─ Different → Check cluster merging
            ├─ Everything merges → Embedding quality issue
            └─ Normal → Unknown issue (share logs)
```

---

## Contact

Deploy the latest code, collect the trace logs, and share them. This will show exactly where production diverges from the working test environment, allowing us to implement a targeted fix.

The diagnostic framework is now complete - we just need to see what's actually happening in production!
