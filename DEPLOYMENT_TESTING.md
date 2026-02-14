# LLM Integration Deployment Testing

## Overview
This document describes how to verify that both Docker and dockerless deployments correctly use the LLM (Ollama with llama2 model) for generating descriptions.

## Fixed Issues

### Issue 1: Docker Deployment Ollama URL
**Problem**: Docker compose was configured to use `http://localhost:11434` for Ollama, which wouldn't work when Ollama runs as a container service.

**Fix**: Changed default to `http://ollama:11434` in docker-compose.yml to point to the ollama container service.

**Location**: `docker-compose.yml` line 176

```yaml
# Before:
- SPRING_AI_OLLAMA_BASE_URL=${SPRING_AI_OLLAMA_BASE_URL:-http://localhost:11434}

# After:
- SPRING_AI_OLLAMA_BASE_URL=${SPRING_AI_OLLAMA_BASE_URL:-http://ollama:11434}
```

## Deployment Modes

### Dockerless Deployment

**How it works:**
1. `OllamaLauncherConfig` starts Ollama container (or warns if Docker unavailable)
2. `OllamaChatConfig` creates ChatModel bean pointing to http://localhost:11434
3. Application profile: NOT "docker"

**Start command:**
```bash
./start-non-docker.sh
```

**Expected logs:**
```
[OllamaLauncherConfig] Starting Ollama launcher...
[OllamaLauncherConfig] Ollama container ollama already running.
[OllamaChatConfig] Creating OllamaChatModel
[OllamaChatConfig]   Base URL: http://localhost:11434
[OllamaChatConfig]   Model: llama2
[LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: true
```

### Docker Deployment

**How it works:**
1. Ollama runs as separate container (when using `--profile ollama`)
2. `OllamaChatConfig` creates ChatModel bean pointing to http://ollama:11434
3. Application profile: "docker"
4. `OllamaLauncherConfig` is DISABLED (@Profile("!docker"))

**Start command (with Ollama):**
```bash
docker-compose --profile ollama up -d
```

**Start command (without Ollama, using external):**
```bash
# Set environment variable to point to external Ollama
export SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434
docker-compose up -d
```

**Expected logs:**
```
[OllamaChatConfig] Creating OllamaChatModel
[OllamaChatConfig]   Base URL: http://ollama:11434
[OllamaChatConfig]   Model: llama2
[LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: true
```

## Testing the LLM Integration

### Step 1: Ensure Ollama is Running

For dockerless:
```bash
# Ollama should be started by OllamaLauncherConfig
# Or manually ensure it's running on localhost:11434
```

For Docker with Ollama profile:
```bash
docker-compose --profile ollama up -d ollama
# Wait for Ollama to be healthy
docker-compose exec ollama ollama pull llama2
```

### Step 2: Get Authentication Token

The API requires JWT authentication. You need to:
1. Register a user or use existing credentials
2. Login to get JWT token
3. Use token in Authorization header

Example:
```bash
# Register (if needed)
curl -X POST http://localhost:8088/taniwha/api/users/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass","email":"test@test.com"}'

# Login to get token
TOKEN=$(curl -X POST http://localhost:8088/taniwha/api/users/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"testpass"}' \
  | jq -r '.token')
```

### Step 3: Test LLM Integration

Send a mapping suggestion request:

```bash
curl -X POST http://localhost:8088/taniwha/api/mappings/suggest \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "elements": [
      {
        "elementName": "BathelIndex_Bathing",
        "sampleValues": ["0", "5", "10"]
      },
      {
        "elementName": "BathelIndex_Toileting", 
        "sampleValues": ["0", "5", "10"]
      }
    ],
    "schema": []
  }'
```

### Step 4: Verify LLM is Used

Check the response for:
1. **LLM-generated descriptions**: Rich, contextual descriptions (not just column names)
2. **SNOMED codes**: Terminology codes selected by TerminologyService

Example expected response (partial):
```json
{
  "success": true,
  "message": "Suggestions computed.",
  "hierarchy": [
    {
      "BathelIndex_Bathing": {
        "description": "The bathing field measures the level of independence...",
        "terminology": "284546000",
        ...
      }
    }
  ]
}
```

Check application logs for:
```
[TerminologyService] Selected SNOMED code 284546000 for term: bathing
[LLMTextGenerator] Generated column description for 'bathing': The bathing field measures...
```

## Troubleshooting

### LLM Disabled
If logs show `LLM enabled: false, ChatModel available: false`:
- Check Ollama is running and accessible at the configured URL
- Verify `llm.enabled=true` in configuration
- Check for errors in OllamaChatConfig initialization

### Connection Refused
If you see "Connection refused" to Ollama:
- **Dockerless**: Check Ollama is running on localhost:11434
- **Docker**: Ensure ollama service is started and healthy
- Verify network connectivity between containers

### Wrong Ollama URL in Docker
If Docker deployment can't connect to Ollama:
- Check `SPRING_AI_OLLAMA_BASE_URL` environment variable
- Should be `http://ollama:11434` when using ollama service
- Should be external URL when not using ollama profile

### Authentication Errors
If you get 401/403 errors:
- Ensure you have valid JWT token
- Token must be included in Authorization header
- Check JWT_SECRET is set correctly in .env

## Configuration Summary

| Mode | Ollama Location | Base URL | Profile | OllamaLauncherConfig |
|------|----------------|----------|---------|---------------------|
| Dockerless | Docker container or host | http://localhost:11434 | (none) | Active |
| Docker with ollama profile | ollama container | http://ollama:11434 | docker | Inactive |
| Docker without ollama profile | External | Custom via env var | docker | Inactive |
