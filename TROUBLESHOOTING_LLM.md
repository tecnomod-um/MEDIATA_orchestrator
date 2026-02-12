# Troubleshooting LLM and Terminology Issues

## Problem: "LLM disabled, returning column name"

### Symptoms
```
[LLMTextGenerator] LLM disabled, returning column name
```

Descriptions in API response are just column names instead of generated text.

### Root Cause
ChatModel bean is not being created by Spring AI autoconfiguration. This happens when:
1. Ollama is not running when the application starts
2. Ollama is not accessible at the configured URL
3. Spring AI can't connect to Ollama during startup

### Solution

**Option 1: Use Startup Script (Recommended)**
```bash
./start-non-docker.sh
```

**Option 2: Manual Startup**
```bash
# 1. Start Ollama FIRST
ollama serve &

# 2. Wait for it to be ready (5-10 seconds)
sleep 10

# 3. Verify Ollama is running
curl http://localhost:11434/
# Should return: Ollama is running

# 4. Check model is downloaded
ollama list | grep llama2

# 5. If model not found, pull it
ollama pull llama2

# 6. NOW start the application
mvn spring-boot:run
```

### Verification

Check application logs at startup:
```
✅ GOOD: [LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: true
❌ BAD:  [LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: false
```

If you see `ChatModel available: false`:
- Ollama was not accessible when Spring Boot started
- Restart the application (Ollama must be running BEFORE app starts)

---

## Problem: Terminology Codes Like "CONCEPT_483956505"

### Symptoms
```json
{
  "terminology": "CONCEPT_483956505"  // Should be real SNOMED like "284546000"
}
```

### Root Cause
TerminologyService is using fallback codes because:
1. Snowstorm is not accessible
2. RDFService (Python bridge) is not running
3. SNOMED search failed

### Solution

**Check Snowstorm Accessibility:**
```bash
# Test Snowstorm connection
curl http://localhost:9100/

# Or check configured URL
curl $SNOWSTORM_API_URL
```

**If Snowstorm is not running:**
1. Start Snowstorm service
2. Verify it's accessible
3. Restart the application

**Configuration Check:**
```bash
# Check what URL is configured
grep snowstorm.api.url src/main/resources/application.properties

# Default is:
# snowstorm.api.url=http://localhost:9100
```

### Verification

Real SNOMED codes:
- ✅ Numeric format: `284546000`, `165232002`
- ❌ Fallback format: `CONCEPT_483956505`, `CONCEPT_003016435`

---

## Problem: Application Starts But No Descriptions Generated

### Symptoms
- Application logs show `ChatModel available: true`
- But descriptions still return column names
- Or API calls hang/timeout

### Possible Causes

**1. Ollama Model Not Loaded**
```bash
# Check if model is loaded
ollama list

# Expected output should include:
# llama2:latest

# If not, pull it:
ollama pull llama2
```

**2. Ollama Running But Slow**
- First LLM call can take 30-60 seconds (model loading)
- Subsequent calls are faster (5-10 seconds)
- Be patient!

**3. Ollama Memory Issues**
- llama2 requires ~4-8GB RAM
- Check system resources
- Consider smaller model if needed

### Verification

Test Ollama directly:
```bash
curl http://localhost:11434/api/generate -d '{
  "model": "llama2",
  "prompt": "Test",
  "stream": false
}'
```

Should return JSON with generated text.

---

##  Quick Diagnostic Checklist

Run these commands to diagnose issues:

```bash
# 1. Check Ollama is running
pgrep ollama
# Should return a process ID

# 2. Check Ollama is accessible
curl http://localhost:11434/
# Should return: Ollama is running

# 3. Check model is downloaded
ollama list | grep llama2
# Should show: llama2:latest

# 4. Test Ollama generation
curl http://localhost:11434/api/generate -d '{"model":"llama2","prompt":"Hi","stream":false}'
# Should return generated text

# 5. Check Snowstorm (optional)
curl http://localhost:9100/
# Should return Snowstorm response

# 6. Check application logs
tail -f logs/application.log | grep LLMTextGenerator
# Should show: ChatModel available: true
```

---

## Performance Expectations

### LLM Text Generation
- **First call:** 30-60 seconds (model loading into memory)
- **Subsequent calls:** 5-15 seconds per description
- **Full mapping (27 columns):** 10-20 minutes total

### Caching
- Descriptions are cached after first generation
- Second mapping of same data: instant (from cache)

### Timeouts
If LLM calls timeout:
1. Check Ollama logs: `tail -f /tmp/ollama.log`
2. Check system resources (RAM, CPU)
3. Consider increasing Spring Boot timeouts
4. Try smaller model if needed

---

## Configuration Reference

### Default (non-Docker)
```properties
llm.enabled=true
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.enabled=true
spring.ai.ollama.chat.options.model=llama2
spring.ai.ollama.chat.options.temperature=0.7

snowstorm.enabled=true
snowstorm.api.url=http://localhost:9100
snowstorm.api.branch=MAIN
```

### Environment Variable Overrides
```bash
# Disable LLM if needed
export LLM_ENABLED=false

# Use different Ollama URL
export SPRING_AI_OLLAMA_BASE_URL=http://custom-host:11434

# Use different model
export SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL=mistral

# Disable Snowstorm
export SNOWSTORM_ENABLED=false
```

---

## Common Error Messages

### "Connection refused" at startup
```
Failed to connect to http://localhost:11434
```
**Solution:** Start Ollama before starting the application

### "Model not found"
```
model 'llama2' not found
```
**Solution:** `ollama pull llama2`

### "Out of memory" or OOMKilled
```
Process killed
```
**Solution:** Ollama needs more RAM. Close other applications or use smaller model.

### "ChatModel available: false"
```
[LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: false
```
**Solution:** Restart application after ensuring Ollama is running

---

## Contact Support

If issues persist after following this guide:
1. Collect logs from startup
2. Run diagnostic checklist
3. Note which step fails
4. Include in support request
