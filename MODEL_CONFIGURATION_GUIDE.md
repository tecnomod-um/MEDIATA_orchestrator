# LLM Model Configuration Guide

## Current Configuration

The application is configured to use `sentence-transformers/all-MiniLM-L6-v2` via DJL ONNX Hub:

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L6-v2
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L6-v2
```

This is the **proven working model** that produces 27 semantic mappings in tests.

---

## Testing Different Models

To test a different embedding model, update both URIs in `application.properties`:

### Option 1: Use DJL ONNX Hub (Recommended)

DJL automatically downloads and caches ONNX-optimized models:

```properties
# Format: djl://ai.djl.huggingface.onnx/MODEL_PATH
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/MODEL_PATH
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/MODEL_PATH
```

**Recommended Models to Try:**

1. **sentence-transformers/all-MiniLM-L6-v2** (Current - WORKS ✅)
   ```properties
   spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L6-v2
   spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L6-v2
   ```
   - **Dimensions**: 384
   - **Quality**: High ✅
   - **Test Results**: 27 mappings, proper semantic keys
   - **Use Case**: General semantic similarity

2. **sentence-transformers/all-mpnet-base-v2** (Larger, potentially better)
   ```properties
   spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-mpnet-base-v2
   spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-mpnet-base-v2
   ```
   - **Dimensions**: 768
   - **Quality**: Higher but slower
   - **Use Case**: Better semantic understanding, needs more compute

3. **sentence-transformers/paraphrase-MiniLM-L6-v2**
   ```properties
   spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/paraphrase-MiniLM-L6-v2
   spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/paraphrase-MiniLM-L6-v2
   ```
   - **Dimensions**: 384
   - **Quality**: Similar to all-MiniLM-L6-v2
   - **Use Case**: Paraphrase detection

**NOT Recommended:**

❌ **intfloat/e5-small-v2** (Caused production issue - embeddings too similar)
```properties
# DO NOT USE - causes all clusters to merge
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/intfloat/e5-small-v2
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/intfloat/e5-small-v2
```
- **Issue**: Requires special "query: " / "passage: " prefixes
- **Result**: Poor quality embeddings → 66/67 clusters merged → 1 mapping
- **Status**: Known bad for this use case

---

### Option 2: Direct HuggingFace URLs (Advanced)

Only use if you know the exact ONNX file locations:

```properties
spring.ai.embedding.transformer.onnx.modelUri=https://huggingface.co/ORG/MODEL/resolve/main/onnx/model.onnx
spring.ai.embedding.transformer.tokenizer.uri=https://huggingface.co/ORG/MODEL/raw/main/tokenizer.json
```

**Note**: Most models don't have pre-built ONNX files. Use DJL instead.

---

## Testing a New Model

### Step 1: Update Configuration
Edit `src/main/resources/application.properties`:

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/YOUR_MODEL_HERE
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/YOUR_MODEL_HERE
```

### Step 2: Clear Cache (Optional)
```bash
rm -rf /tmp/spring-ai-onnx-model/*
```

### Step 3: Run Tests
```bash
mvn -Dtest=MappingServiceReportTest test
```

### Step 4: Check Results
Look for:
```
Total mappings found: X
Mapping keys: [...]
Expected categories found: X out of 7
```

**Good Model Criteria:**
- ✅ 25-30 mappings (not 1, not 100+)
- ✅ Semantic keys: sex, bath, type, dress, feed, etc.
- ✅ 6-7 categories identified
- ✅ Valid value mappings: 25/25

**Bad Model Signs:**
- ❌ Only 1 mapping (over-merging, embeddings too similar)
- ❌ 80+ mappings (under-merging, embeddings too different)
- ❌ Nonsensical keys like "af", single letters
- ❌ Low category count (1-3 out of 7)

### Step 5: Check Logs
```
[TRACE] clusterColumns: merged X clusters, final count: Y
```

**Good**: 5-10 merges, 25-30 final clusters  
**Bad**: 66 merges → 1 cluster (embeddings too similar)  
**Bad**: 0 merges → 67 clusters (embeddings too different)

---

## Model Comparison Test

To systematically compare models, create a test configuration:

```java
@TestConfiguration
static class TestConfig {
    @Bean
    public EmbeddingModel embeddingModel() {
        TransformersEmbeddingModel.TransformersEmbeddingModelBuilder builder = 
            TransformersEmbeddingModel.builder();
        
        // Test different models
        builder.modelUri("djl://ai.djl.huggingface.onnx/MODEL_PATH");
        builder.tokenizerUri("djl://ai.djl.huggingface.onnx/MODEL_PATH");
        
        return builder.build();
    }
}
```

Then run tests with each model and compare results.

---

## Production Deployment

When you find a model that works well in tests:

1. **Update application.properties** with the new model URIs
2. **Rebuild**: `mvn clean package`
3. **Test locally** with controller tests
4. **Deploy to production**
5. **Monitor logs** for clustering results:
   ```
   [TRACE] clusterColumns: merged X clusters, final count: Y
   ```
6. **Verify API response** returns proper mappings

---

## Recommended Workflow

1. Start with **all-MiniLM-L6-v2** (current, proven)
2. Try **all-mpnet-base-v2** for potentially better quality
3. Compare results using the test suite
4. Choose based on:
   - Mapping quality (25-30 mappings)
   - Semantic accuracy (proper keys)
   - Performance (startup time, memory)
5. Deploy winner to production

---

## Troubleshooting

### Model Download Fails
```
Failed to cache the resource: URL [djl://...]
```

**Solutions:**
1. Check internet connectivity
2. Verify model name is correct
3. Try direct HuggingFace URL (if ONNX files exist)
4. Check firewall/proxy settings

### Too Many Mappings (80+)
**Cause**: Embeddings too different, no clusters merging

**Solutions:**
1. Increase similarity threshold (currently 0.56)
2. Try a different model
3. Check if embeddings are being normalized

### Too Few Mappings (1-5)
**Cause**: Embeddings too similar, everything merging

**Solutions:**
1. **Try different model** (current issue with e5-small-v2)
2. Check if model requires special prompting
3. Verify embeddings are diverse (check logs)

### Poor Semantic Quality
**Cause**: Model not capturing semantic similarity well

**Solutions:**
1. Try larger model (all-mpnet-base-v2)
2. Use domain-specific model if available
3. Check if model needs fine-tuning

---

## Current Status

**Production Model**: sentence-transformers/all-MiniLM-L6-v2  
**Status**: ✅ Working (27 mappings, proper semantic keys)  
**Quality**: High (100% valid value mappings, 6/7 categories)  
**Performance**: Fast (384 dims, efficient)  

**No changes needed unless experimenting with improvements.**
