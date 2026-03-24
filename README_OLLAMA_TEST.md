# Running MappingServiceReportTest with Ollama

The `MappingServiceReportTest` validates the complete mapping functionality including LLM text generation via Ollama.

## Prerequisites

The test requires Ollama to be running with the llama2 model loaded.

## Option 1: Run Ollama via Docker Compose (Recommended)

```bash
# Start Ollama service
docker compose --profile ollama up -d

# Wait for service to be healthy
docker compose ps

# Pull llama2 model
docker compose exec ollama ollama pull llama2

# Run the test
mvn -Dtest=MappingServiceReportTest test
```

## Option 2: Run Ollama Locally

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Start Ollama service  
ollama serve &

# Pull llama2 model
ollama pull llama2

# Verify Ollama is running
curl http://localhost:11434/

# Run the test
mvn -Dtest=MappingServiceReportTest test
```

## What the Test Does

1. **Uses Real Configuration**: Test uses `application-test.properties` which mirrors deployment config
2. **Spring Boot Autoconfiguration**: ChatModel is autoconfigured by Spring AI Ollama starter (same as deployment)
3. **Real LLM Generation**: Generates actual descriptions using Ollama llama2 model
4. **No Mocks**: Except RDFService (Snowstorm), everything else is real including LLM text generation

## Expected Output

```
[TEST] Creating LLMTextGenerator
[TEST] ChatModel available: true
[TEST] ChatModel class: org.springframework.ai.ollama.OllamaChatModel
[TEST] LLM enabled: true
[LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: true
[LLMTextGenerator] Generating description with LLM

Mapping: bath
  Terminology: 284546000
  Description: [Real LLM-generated medical description]
```

## If Ollama Not Available

If Ollama is not running, the test will still pass but use fallback behavior:

```
[TEST] ChatModel available: false
[LLMTextGenerator] LLM disabled, returning column name
Description: bath  ← Fallback
```

This is the same fallback that happens in deployment when Ollama is not configured.

## Troubleshooting

### ChatModel not available despite Ollama running

1. Check Ollama is responding:
```bash
curl http://localhost:11434/
# Should return: Ollama is running
```

2. Check model is loaded:
```bash
ollama list
# Should show llama2
```

3. Check Spring AI can connect:
```bash
# In test logs, look for autoconfiguration messages
grep "OllamaChatModel" target/surefire-reports/*.txt
```

### Test timeout

First run may take time as Ollama loads the model into memory. Subsequent runs will be faster.

## Configuration

Test uses `src/test/resources/application-test.properties`:

```properties
llm.enabled=true
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.enabled=true
spring.ai.ollama.chat.options.model=llama2
```

This matches deployment configuration in `src/main/resources/application.properties`.
