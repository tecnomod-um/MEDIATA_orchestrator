#!/bin/bash
# Script to run MappingServiceReportTest with Ollama auto-launch
# Mirrors what OllamaLauncherConfig does in deployment

set -e

echo "=== Starting Ollama for Test (mimics OllamaLauncherConfig) ==="

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "ERROR: Docker not available. Cannot start Ollama."
    exit 1
fi

# Configuration (matches OllamaLauncherConfig defaults)
OLLAMA_IMAGE="ollama/ollama:latest"
OLLAMA_CONTAINER="ollama-test"
OLLAMA_PORT="11434"
OLLAMA_MODEL="llama2"
OLLAMA_TIMEOUT=120

echo "1. Checking if Ollama image exists..."
if ! docker image inspect "$OLLAMA_IMAGE" &> /dev/null; then
    echo "   Pulling Ollama image..."
    docker pull "$OLLAMA_IMAGE"
fi
echo "   ✓ Ollama image ready"

echo "2. Starting Ollama container..."
# Check if container exists
if docker ps -a --format '{{.Names}}' | grep -q "^${OLLAMA_CONTAINER}$"; then
    echo "   Container exists, checking status..."
    if docker ps --format '{{.Names}}' | grep -q "^${OLLAMA_CONTAINER}$"; then
        echo "   ✓ Ollama container already running"
    else
        echo "   Starting existing container..."
        docker start "$OLLAMA_CONTAINER"
    fi
else
    echo "   Creating and starting new container..."
    docker run -d \
        --name "$OLLAMA_CONTAINER" \
        -p "${OLLAMA_PORT}:11434" \
        -v ollama-test-models:/root/.ollama \
        "$OLLAMA_IMAGE"
fi

echo "3. Waiting for Ollama to be ready (timeout: ${OLLAMA_TIMEOUT}s)..."
START_TIME=$(date +%s)
while true; do
    if curl -s http://localhost:${OLLAMA_PORT}/ > /dev/null 2>&1; then
        echo "   ✓ Ollama is ready at http://localhost:${OLLAMA_PORT}/"
        break
    fi
    
    CURRENT_TIME=$(date +%s)
    ELAPSED=$((CURRENT_TIME - START_TIME))
    
    if [ $ELAPSED -gt $OLLAMA_TIMEOUT ]; then
        echo "   ERROR: Ollama did not start within ${OLLAMA_TIMEOUT} seconds"
        exit 1
    fi
    
    echo "   Waiting... (${ELAPSED}s elapsed)"
    sleep 2
done

echo "4. Checking if model $OLLAMA_MODEL is available..."
if docker exec "$OLLAMA_CONTAINER" ollama list | grep -q "$OLLAMA_MODEL"; then
    echo "   ✓ Model $OLLAMA_MODEL already available"
else
    echo "   Pulling model $OLLAMA_MODEL (this may take a while, ~3.8GB)..."
    docker exec "$OLLAMA_CONTAINER" ollama pull "$OLLAMA_MODEL"
    echo "   ✓ Model $OLLAMA_MODEL downloaded"
fi

echo ""
echo "=== Ollama Ready - Running Test ==="
echo ""

# Run the test
mvn -Dtest=MappingServiceReportTest test

echo ""
echo "=== Test Complete ==="
echo "Note: Ollama container '$OLLAMA_CONTAINER' is still running."
echo "To stop it: docker stop $OLLAMA_CONTAINER"
echo "To remove it: docker rm $OLLAMA_CONTAINER"
