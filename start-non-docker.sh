#!/bin/bash

# Startup script for non-Docker deployment
# The application will automatically launch Ollama via OllamaLauncherConfig

set -e

echo "=== Non-Docker Deployment Startup ==="
echo ""
echo "Starting application..."
echo "OllamaLauncherConfig will automatically:"
echo "  1. Start Ollama Docker container"
echo "  2. Pull llama2 model if needed"
echo "  3. Initialize LLMTextGenerator with ChatModel"
echo ""
echo "Expected logs:"
echo "  [OllamaLauncherConfig] Starting Ollama launcher..."
echo "  [OllamaLauncherConfig] Ollama is ready..."
echo "  [OllamaChatConfig] Creating OllamaChatModel"
echo "  [LLMTextGenerator] Initialized. LLM enabled: true, ChatModel available: true"
echo ""

# Start the application - OllamaLauncherConfig handles Ollama setup
mvn spring-boot:run
