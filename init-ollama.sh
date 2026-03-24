#!/bin/bash
# Initialize Ollama with required models

echo "Waiting for Ollama service to be ready..."
sleep 5

echo "Pulling llama2 model..."
docker exec mediata-ollama ollama pull llama2

echo "Ollama initialization complete!"
