#!/bin/bash

# Startup script for non-Docker deployment
# Ensures Ollama is running before starting the application

set -e

echo "=== Non-Docker Deployment Startup ==="
echo ""

# Check if Ollama is installed
if ! command -v ollama &> /dev/null; then
    echo "ERROR: Ollama is not installed."
    echo "Install with: curl -fsSL https://ollama.com/install.sh | sh"
    exit 1
fi

# Start Ollama if not already running
echo "Checking Ollama status..."
if ! pgrep -x "ollama" > /dev/null; then
    echo "Starting Ollama service..."
    ollama serve > /tmp/ollama.log 2>&1 &
    OLLAMA_PID=$!
    echo "Ollama started with PID: $OLLAMA_PID"
else
    echo "Ollama is already running"
fi

# Wait for Ollama to be ready
echo "Waiting for Ollama to be ready..."
MAX_WAIT=30
WAIT_COUNT=0
while ! curl -s http://localhost:11434/ > /dev/null; do
    sleep 1
    WAIT_COUNT=$((WAIT_COUNT + 1))
    if [ $WAIT_COUNT -ge $MAX_WAIT ]; then
        echo "ERROR: Ollama did not start within $MAX_WAIT seconds"
        exit 1
    fi
    echo -n "."
done
echo ""
echo "✓ Ollama is running on http://localhost:11434"

# Check if llama2 model is downloaded
echo "Checking llama2 model..."
if ollama list | grep -q "llama2"; then
    echo "✓ llama2 model is already downloaded"
else
    echo "Downloading llama2 model (this may take a few minutes)..."
    ollama pull llama2
    echo "✓ llama2 model downloaded"
fi

# Check Snowstorm (optional)
echo ""
echo "Checking Snowstorm status (optional)..."
if curl -s http://localhost:9100/ > /dev/null 2>&1; then
    echo "✓ Snowstorm is accessible on http://localhost:9100"
else
    echo "⚠  Snowstorm is not accessible on http://localhost:9100"
    echo "   Terminology codes will use fallback values (CONCEPT_XXXXXXX)"
    echo "   Start Snowstorm if you want real SNOMED CT codes"
fi

echo ""
echo "=== All prerequisites ready ===" 
echo ""
echo "Starting application..."
echo "Expected log: [LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: true"
echo ""

# Start the application
mvn spring-boot:run
