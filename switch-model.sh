#!/bin/bash
# Script to easily switch between embedding models for testing
# Usage: ./switch-model.sh [model-name]
# Available models: default, mpnet, paraphrase, l12

set -e

CONFIG_FILE="src/main/resources/application.properties"

# Function to uncomment lines matching a pattern
uncomment_model() {
    local pattern="$1"
    sed -i "s/#\(spring.ai.embedding.transformer.*${pattern}.*\)/\1/" "$CONFIG_FILE"
}

# Function to comment all model URIs
comment_all_models() {
    sed -i "s/^spring.ai.embedding.transformer.onnx.modelUri=/#&/" "$CONFIG_FILE"
    sed -i "s/^spring.ai.embedding.transformer.tokenizer.uri=/#&/" "$CONFIG_FILE"
}

# Show usage
if [ "$#" -eq 0 ]; then
    echo "Usage: ./switch-model.sh [model]"
    echo ""
    echo "Available models:"
    echo "  default      - Spring AI built-in all-MiniLM-L6-v2 (384 dim, fastest)"
    echo "  mpnet        - all-mpnet-base-v2 (768 dim, larger/better)"
    echo "  paraphrase   - paraphrase-MiniLM-L6-v2 (384 dim, specialist)"
    echo "  l12          - all-MiniLM-L12-v2 (384 dim, deeper)"
    echo ""
    echo "Example: ./switch-model.sh mpnet"
    exit 1
fi

MODEL="$1"

echo "Switching to model: $MODEL"

# Comment out all model URIs first
comment_all_models

case "$MODEL" in
    default)
        echo "✓ Using Spring AI default (all-MiniLM-L6-v2)"
        echo "  No custom URIs - Spring AI handles download automatically"
        ;;
    mpnet)
        echo "✓ Switching to all-mpnet-base-v2"
        uncomment_model "all-mpnet-base-v2"
        ;;
    paraphrase)
        echo "✓ Switching to paraphrase-MiniLM-L6-v2"
        uncomment_model "paraphrase-MiniLM-L6-v2"
        ;;
    l12)
        echo "✓ Switching to all-MiniLM-L12-v2"
        uncomment_model "all-MiniLM-L12-v2"
        ;;
    *)
        echo "Error: Unknown model '$MODEL'"
        echo "Use: default, mpnet, paraphrase, or l12"
        exit 1
        ;;
esac

echo ""
echo "Model switched! Run tests with:"
echo "  mvn -Dtest=MappingServiceReportTest test"
echo ""
