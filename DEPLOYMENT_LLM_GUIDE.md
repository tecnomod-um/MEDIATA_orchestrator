# LLM Integration Deployment Guide

## Overview

MEDIATA Orchestrator uses **OpenMeditron/Meditron3-Gemma2-2B** via the OpenMed
Terminology Service (`mediata-openmed/`) to generate short clinical descriptions
for dataset values.  The same service also runs a biomedical NER model
(`OpenMed/OpenMed-NER-DiseaseDetect-SuperClinical-434M`) for SNOMED-searchable
term inference.

Ollama is still supported as an optional supplementary LLM provider; it is no
longer required for description generation.

## Architecture

```
Client Request
    ↓
MappingController
    ↓
MappingService
    ├─→ OpenMedTerminologyService → OpenMed /infer_batch  (NER, ~434M params)
    │                                   ↓ search terms
    │       TerminologyService → Snowstorm (SNOMED CT codes)
    └─→ OpenMedDescriptionService → OpenMed /describe_batch
                                        ↓ Meditron3-Gemma2-2B (~2B params)
                                   plain-language descriptions
    ↓
Response with Terminology + Descriptions
```

## Deployment Modes

### 1. Dockerless Deployment (Local)

The OpenMed service is **launched automatically** by the Java application
(`OpenMedLauncherConfig`) at startup.  No manual step is required.

**Prerequisites:**
- Java 17+
- Maven 3.6+
- Python 3.11+
- Snowstorm running on localhost:9100 (optional but recommended)

**Setup:**

```bash
# 1. (Optional) Start Snowstorm for SNOMED CT codes
#    If omitted, codes fall back to CONCEPT_XXXXXXX placeholders.

# 2. Build and run the application
mvn spring-boot:run
#    On first run the application will:
#      - Create mediata-openmed/.venv
#      - Install fastapi, uvicorn, transformers, torch
#      - Start uvicorn on port 8002
#      - Download ~5 GB of model weights on first inference call
```

Or use the helper script:
```bash
./start-non-docker.sh
```

**Verify OpenMed is running:**
```bash
curl http://localhost:8002/health
# Expected: {"status":"ok","ner_model_loaded":true,"describe_model_loaded":true,...}
```

### 2. Docker Deployment

All services are managed by `docker-compose.yml`.  The OpenMed service is
defined as `mediata-openmed` and the orchestrator depends on it being healthy
before starting.

**Prerequisites:**
- Docker Engine 20.10+
- Docker Compose

**Setup:**

```bash
# 1. Configure environment
cp .env.example .env
#    Set JWT_SECRET to a 32+ character value

# 2. Build and deploy
./build-and-deploy.sh

# 3. Monitor startup (OpenMed model downloads happen on first inference)
docker compose logs -f openmed
```

**Verify OpenMed container:**
```bash
curl http://localhost:8002/health
# Expected: {"status":"ok","ner_model_loaded":true,"describe_model_loaded":true,...}
```

## Configuration

### Environment Variables

```bash
# OpenMed service
export OPENMED_URL=http://localhost:8002        # where orchestrator finds the service
export OPENMED_HOST_PORT=8002                   # host port for Docker mapping
export OPENMED_ENABLED=true                     # set false to disable NER + descriptions
export OPENMED_LAUNCHER_ENABLED=true            # set false to skip auto-launching locally
export OPENMED_TIMEOUT_MS=300000                # 5 min – Meditron3 ~10 s/value on CPU
export DESCRIPTION_TIMEOUT_SECONDS=360          # Java CompletableFuture timeout

# Snowstorm (SNOMED CT)
export SNOWSTORM_ENABLED=true
export SNOWSTORM_API_URL=http://localhost:9100
export SNOWSTORM_BRANCH=MAIN

# Ollama (optional supplementary LLM)
export LLM_ENABLED=false                        # set true if you want Ollama descriptions
export SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434
```

### Disabling OpenMed

If you want to run without NER-based terminology inference and descriptions:

```bash
export OPENMED_ENABLED=false
```

When disabled:
- Description generation uses a simple text-normalisation fallback
- Terminology search terms are derived by camelCase/snake_case splitting
- Snowstorm SNOMED lookup still works

## Example Output

```json
{
  "name": "toilet",
  "terminology": "284546000",
  "description": "Ability to use toilet.",
  "values": [
    {
      "value": "0",
      "terminology": "371152001",
      "description": "Unable to use toilet independently."
    },
    {
      "value": "10",
      "terminology": "371153006",
      "description": "Independent use of toilet."
    }
  ]
}
```

## Performance

### Expected Response Times (CPU)

| Operation | Time |
|---|---|
| NER inference per column (`/infer_batch`) | ~0.5 s |
| Description generation per value (`/describe_batch`) | ~10 s |
| SNOMED concept lookup | <1 s |
| OpenMed model load (first startup) | ~30 s |
| Meditron3 weight download (one-time) | ~5 GB, 5-15 min |

### Resource Requirements

**OpenMed service (Meditron3-Gemma2-2B):**
- Disk: ~5 GB (model weights, cached by HuggingFace)
- RAM: ~5 GB (bfloat16 weights)
- CPU: 4 cores recommended (PyTorch uses `os.cpu_count()` threads)

## Troubleshooting

### OpenMed service doesn't start (dockerless)

```bash
# Check Python is installed and at 3.11+
python3 --version

# Check service logs
curl http://localhost:8002/health

# Manually start if needed
cd mediata-openmed
python3 -m uvicorn main:app --host 0.0.0.0 --port 8002
```

### OpenMed container not healthy (Docker)

```bash
# Check startup logs (model loading takes ~30 s, downloads on first run)
docker compose logs -f openmed

# Check health
curl http://localhost:8002/health
```

### Descriptions are using fallback text

This happens when the OpenMed service is in fallback mode (model not loaded).
Check the health endpoint:
```bash
curl http://localhost:8002/health
# "describe_model_loaded": false → model still loading or failed
```

### Slow first mapping request

The Meditron3-Gemma2-2B model is loaded once at startup; the weights (~5 GB)
are downloaded from HuggingFace on the very first run.  Subsequent starts use
the cached weights.  First **inference** after load takes normal time (~10 s/value).

## Optional: Ollama (supplementary LLM)

Ollama is no longer needed for description generation but can still be
configured if you want an additional LLM provider via Spring AI:

```bash
# Install
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model
ollama pull llama3

# Enable in environment
export LLM_ENABLED=true
export SPRING_AI_OLLAMA_BASE_URL=http://localhost:11434

# Docker: start Ollama container
docker compose --profile ollama up -d ollama
docker compose exec ollama ollama pull llama3
```

