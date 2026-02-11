# Production Embedding Quality Diagnosis

## What We Know So Far

### ✅ Production Trace Logs Received
```
[TRACE] clusterColumns: formed 67 initial concept-based clusters
[TRACE] clusterColumns: merged 66 clusters (threshold=0.56), final count: 1
```

**This is the smoking gun!** 66 out of 67 clusters merged, meaning almost all cluster centroids have cosine similarity >= 0.56 with each other.

### 🎯 Root Cause Identified
**The embeddings in production are too similar to each other**, causing massive over-merging. This is NOT a code bug - it's an embedding quality issue.

---

## Test vs Production Comparison

### Test Environment (Working Correctly)
```
[TRACE-EMBED] Column 'type: ischemic, hemorrhagic' -> 
  vector[0..5]=[0.0203, -0.0289, 0.0020, 0.0552, 0.0452, -0.0359]
  magnitude=1.0000, dim=384

[TRACE-CENTROID] Cluster 1: 
  centroid[0..5]=[0.0203, -0.0289, 0.0020, 0.0552, 0.0452, -0.0359]
  magnitude=1.0000, 1 cols

[TRACE-MERGE] Merging cluster 1 (similarity=0.5683)
[TRACE-MERGE] Merging cluster 2 (similarity=0.7782)
[TRACE-MERGE] Merging cluster 3 (similarity=0.8386)
[TRACE-MERGE] Merging cluster 4 (similarity=0.5702)
[TRACE-MERGE] Merging cluster 5 (similarity=0.5946)

[TRACE] similarity stats - min=-0.0715, max=0.8386, avg=0.1702, comparisons=387
[TRACE] merged 5 clusters, final count: 27
```

**Observations:**
- ✅ Embeddings are diverse: `[0.0203, -0.0289, ...]` vs `[-0.0085, 0.0207, ...]` vs `[-0.0752, 0.0400, ...]`
- ✅ Magnitude normalized to 1.0
- ✅ Similarities range widely: -0.0715 to 0.8386
- ✅ Average similarity is LOW (0.1702)
- ✅ Only 5 merges (out of 387 comparisons)

### Production (Broken - Expected Logs)

Deploy the latest code and you should see something like:

**Scenario A: All Zero Vectors (Most Likely)**
```
[TRACE-EMBED] Column 'type: ischemic, hemorrhagic' -> 
  vector[0..5]=[0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000]
  magnitude=0.0000, dim=384

[TRACE-CENTROID] Cluster 1: 
  centroid[0..5]=[0.0000, 0.0000, 0.0000, 0.0000, 0.0000, 0.0000]
  magnitude=0.0000, 1 cols

[TRACE] similarity stats - min=1.0000, max=1.0000, avg=1.0000, comparisons=2211
[TRACE] merged 66 clusters, final count: 1
```

**What this means:** EmbeddingsClient is returning zero vectors for everything. Cosine similarity of two zero vectors is undefined but often defaults to 1.0, causing everything to merge.

**Scenario B: All Identical Vectors**
```
[TRACE-EMBED] Column 'type: ischemic, hemorrhagic' -> 
  vector[0..5]=[0.5000, 0.5000, 0.5000, 0.5000, 0.5000, 0.5000]
  magnitude=1.0000, dim=384

[TRACE-EMBED] Column 'sex: f, m' -> 
  vector[0..5]=[0.5000, 0.5000, 0.5000, 0.5000, 0.5000, 0.5000]
  magnitude=1.0000, dim=384

[TRACE] similarity stats - min=1.0000, max=1.0000, avg=1.0000, comparisons=2211
```

**What this means:** The embedding model is not working and returns the same vector for everything.

**Scenario C: Degenerate Embeddings (High Similarity)**
```
[TRACE-EMBED] Column 'type: ischemic, hemorrhagic' -> 
  vector[0..5]=[0.1234, 0.5678, -0.2345, 0.6789, ...]
  magnitude=1.0000, dim=384

[TRACE-EMBED] Column 'sex: f, m' -> 
  vector[0..5]=[0.1245, 0.5689, -0.2356, 0.6790, ...]
  magnitude=1.0000, dim=384

[TRACE] similarity stats - min=0.7500, max=0.9500, avg=0.8500, comparisons=2211
```

**What this means:** Embeddings are being generated, but they're all very similar to each other (avg similarity 0.85 vs test's 0.17).

---

## What to Do Next

### Step 1: Deploy Latest Code
```bash
git pull origin copilot/add-llms-integration
mvn clean package -DskipTests
# Deploy and restart
```

### Step 2: Send Test Request
```bash
curl 'https://semantics.inf.um.es/taniwha-ws/api/mappings/suggest' \
  -H 'Content-Type: application/json' \
  --data-raw '{ ... your 84-element request ... }'
```

### Step 3: Collect Embedding Logs
```bash
# Get embedding vectors (first 3)
grep "\[TRACE-EMBED\]" /path/to/application.log

# Get cluster centroids (first 3)
grep "\[TRACE-CENTROID\]" /path/to/application.log

# Get similarity statistics
grep "similarity stats" /path/to/application.log
```

### Step 4: Analyze the Results

**Check if vectors are all zeros:**
```bash
grep "\[TRACE-EMBED\]" app.log | grep "0.0000, 0.0000, 0.0000"
```

**Check average similarity:**
```bash
grep "similarity stats - min" app.log
```

**Expected (good):** `avg=0.1702`  
**Actual (if broken):** `avg=0.8500` or `avg=1.0000`

### Step 5: Fix Based on Results

**If all zeros → EmbeddingsClient is broken:**
- Check if `embeddingsClient.embed()` is catching exceptions and returning zeros
- Check EmbeddingsClient error handling code
- Look for fallback logic returning zero vectors

**If all identical → Model not loading properly:**
- Model file corrupted or incomplete
- Wrong model configuration
- Tokenizer not working

**If high average similarity → Wrong model or configuration:**
- Model is too simple/generic
- Model needs different input format
- Model dimensionality mismatch

---

## Quick Diagnosis Commands

```bash
# See first 3 embedding vectors
grep "\[TRACE-EMBED\]" app.log | head -3

# See first 3 cluster centroids
grep "\[TRACE-CENTROID\]" app.log | head -3

# See similarity statistics
grep "similarity stats" app.log

# Count how many zero vectors
grep "\[TRACE-EMBED\]" app.log | grep -c "0.0000, 0.0000, 0.0000"

# Check if all vectors identical (should return many duplicates if broken)
grep "\[TRACE-EMBED\]" app.log | cut -d'[' -f2 | cut -d']' -f1 | sort | uniq -c | sort -rn | head -5
```

---

## Expected Output to Share

Please share these specific logs:

1. **First 3 embedding vectors:**
```bash
grep "\[TRACE-EMBED\]" app.log | head -3
```

2. **First 3 cluster centroids:**
```bash
grep "\[TRACE-CENTROID\]" app.log | head -3
```

3. **Similarity statistics:**
```bash
grep "similarity stats" app.log
```

4. **Merge operations:**
```bash
grep "\[TRACE-MERGE\]" app.log | head -10
```

This will show exactly why production embeddings cause everything to merge!
