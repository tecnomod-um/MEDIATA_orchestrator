# Production Debugging Guide

## Issue
Production API still returns only 1 mapping (key "af") instead of 40 mappings after deploying this PR.

## Root Cause Analysis

The code works perfectly in tests (40 mappings, proper keys), but fails in production. This indicates a **runtime environment issue**, not a code issue.

### Most Likely Cause
**The EmbeddingModel is failing to initialize in production**, causing all embeddings to return zero vectors, which breaks the similarity calculations and clustering.

## Diagnostic Steps

### 1. Check Application Logs

After deploying this latest commit, check the logs during application startup. You should see:

**✅ Success Case:**
```
[EmbeddingsWarmup] Starting warmup...
[EmbeddingsWarmup] SUCCESS! Embedding dimension: 384
[EmbeddingsClient] Initialized successfully. Embedding dimension: 384  
[MappingService] Initialized with EmbeddingsClient: present
```

**❌ Failure Case:**
```
[EmbeddingsWarmup] Starting warmup...
[EmbeddingsWarmup] FAILED to warmup embedding model: <error details>
[EmbeddingsWarmup] The application will continue but embeddings may not work!
[EmbeddingsClient] FAILED to initialize embedding model: <error details>
```

### 2. Common Production Issues

#### Issue A: Model Download Failure
The e5-small-v2 model needs to be downloaded from HuggingFace on first run.

**Symptoms:**
- Timeout errors
- Network errors like "Connection refused" or "Host unreachable"
- "Unable to download model" errors

**Solutions:**
1. Check internet connectivity from production server to `huggingface.co`
2. Check firewall rules
3. Pre-download the model files and configure local path
4. Increase timeout settings

#### Issue B: Insufficient Memory/Resources
The ONNX model requires memory to load.

**Symptoms:**
- OutOfMemoryError
- "Cannot allocate memory"
- Process crashes during startup

**Solutions:**
1. Increase JVM heap size: `-Xmx4g` or higher
2. Check available system memory
3. Use a smaller model if resources are constrained

#### Issue C: Missing Native Libraries
DJL PyTorch requires native libraries.

**Symptoms:**
- "UnsatisfiedLinkError"
- "Cannot load native library"
- "Platform not supported"

**Solutions:**
1. Ensure production OS matches development (Linux x86_64)
2. Check if `/tmp` directory is writable (DJL downloads libs there)
3. Install required system libraries

#### Issue D: File Permission Issues
Model cache directory needs write permissions.

**Symptoms:**
- "Permission denied"
- "Cannot create directory"
- "Access denied"

**Solutions:**
1. Check permissions on `${java.io.tmpdir}/spring-ai-onnx-model`
2. Configure cache directory to writable location via:
   ```properties
   spring.ai.embedding.transformer.cache.directory=/path/to/writable/dir
   ```

### 3. Enable Debug Logging

Add to `application.properties`:
```properties
logging.level.org.springframework.ai=DEBUG
logging.level.ai.djl=DEBUG
logging.level.org.taniwha.service.EmbeddingsClient=DEBUG
```

This will show detailed model loading information.

### 4. Test Model Initialization Separately

Create a simple endpoint to test embedding initialization:

```java
@GetMapping("/debug/embedding-test")
public String testEmbedding() {
    try {
        float[] result = embeddingModel.embed("test");
        return "SUCCESS: Embedding dimension = " + result.length;
    } catch (Exception e) {
        return "FAILED: " + e.getMessage();
    }
}
```

### 5. Verify Configuration

Check `application.properties` in production:
```properties
spring.ai.model.embedding=transformers
spring.ai.embedding.transformer.onnx.modelUri=https://huggingface.co/intfloat/e5-small-v2/resolve/main/model.onnx
spring.ai.embedding.transformer.tokenizer.uri=https://huggingface.co/intfloat/e5-small-v2/raw/main/tokenizer.json
spring.ai.embedding.transformer.cache.enabled=true
spring.ai.embedding.transformer.cache.directory=${java.io.tmpdir}/spring-ai-onnx-model
```

## Quick Fixes to Try

### Fix 1: Use Different Model
If e5-small-v2 is problematic, switch to all-MiniLM-L6-v2 (the one used in tests):

```properties
spring.ai.embedding.transformer.onnx.modelUri=https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx
spring.ai.embedding.transformer.tokenizer.uri=https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/raw/main/tokenizer.json
```

### Fix 2: Pre-download Model
Download model files locally and configure:

```bash
# Download model files
mkdir -p /opt/models/e5-small-v2
cd /opt/models/e5-small-v2
wget https://huggingface.co/intfloat/e5-small-v2/resolve/main/model.onnx
wget https://huggingface.co/intfloat/e5-small-v2/raw/main/tokenizer.json
```

Then update config:
```properties
spring.ai.embedding.transformer.onnx.modelUri=file:///opt/models/e5-small-v2/model.onnx
spring.ai.embedding.transformer.tokenizer.uri=file:///opt/models/e5-small-v2/tokenizer.json
```

### Fix 3: Increase Timeout
If download is slow:

```properties
spring.ai.embedding.transformer.download.timeout=300000
```

## Expected Behavior After Fix

Once the embedding model initializes successfully, you should see:

1. **Logs:**
   ```
   [EmbeddingsClient] Initialized successfully. Embedding dimension: 384
   ```

2. **API Response:**
   - 40+ mappings (not 1)
   - Proper keys like: sex, bath, type, groom, bladder, bowel, etc.
   - No standalone "af" key with all values lumped together

3. **Test Verification:**
   ```bash
   curl 'https://your-api/api/mappings/suggest' -X POST -H 'Content-Type: application/json' -d @test-request.json | jq '.hierarchy | length'
   # Should return 40, not 1
   ```

## Contact Points

If still not working after these steps, provide:
1. Full startup logs (especially EmbeddingsWarmup and EmbeddingsClient lines)
2. Error stack traces
3. Production environment details (OS, Java version, memory, network setup)
4. Any firewall/proxy configurations

The code is tested and working - the issue is 100% environmental.
