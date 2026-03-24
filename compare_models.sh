#!/bin/bash

echo "=================================================================================================="
echo "EMBEDDING MODEL COMPARISON TEST"
echo "=================================================================================================="
echo ""

declare -a MODELS=(
  "all-MiniLM-L6-v2 (BASELINE - DEFAULT)||Spring AI built-in default model"
  "all-mpnet-base-v2|https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/onnx/model.onnx|Larger, potentially better quality"
  "paraphrase-MiniLM-L6-v2|https://huggingface.co/sentence-transformers/paraphrase-MiniLM-L6-v2/resolve/main/onnx/model.onnx|Optimized for paraphrase detection"
  "all-MiniLM-L12-v2|https://huggingface.co/sentence-transformers/all-MiniLM-L12-v2/resolve/main/onnx/model.onnx|Larger MiniLM with more layers"
)

RESULTS_FILE="/tmp/model_comparison_results.txt"
echo "Model Comparison Results" > $RESULTS_FILE
echo "========================" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

# Test each model
for model_info in "${MODELS[@]}"; do
    IFS='|' read -r -a arr <<< "$model_info"
    MODEL_NAME="${arr[0]}"
    MODEL_URI="${arr[1]}"
    MODEL_DESC="${arr[2]}"
    
    echo ""
    echo "=================================================================================================="
    echo "Testing: $MODEL_NAME"
    echo "Description: $MODEL_DESC"
    if [ -z "$MODEL_URI" ]; then
        echo "URI: (Using Spring AI defaults)"
    else
        echo "URI: $MODEL_URI"
    fi
    echo "=================================================================================================="
    
    echo "Running MappingServiceReportTest..."
    if [ -z "$MODEL_URI" ]; then
        # Use default (no env vars set)
        unset EMBEDDING_MODEL_URI
        unset EMBEDDING_TOKENIZER_URI
        TEST_OUTPUT=$(mvn -Dtest=MappingServiceReportTest test 2>&1)
    else
        # Set env vars for custom model
        export EMBEDDING_MODEL_URI="$MODEL_URI"
        export EMBEDDING_TOKENIZER_URI="${MODEL_URI/model.onnx/tokenizer.json}"
        TEST_OUTPUT=$(mvn -Dtest=MappingServiceReportTest test 2>&1)
        unset EMBEDDING_MODEL_URI
        unset EMBEDDING_TOKENIZER_URI
    fi
    TEST_RESULT=$?
    
    # Parse results from output
    TOTAL_MAPPINGS=$(echo "$TEST_OUTPUT" | grep "Total mappings found:" | grep -oP '\d+' | head -1)
    CATEGORIES_FOUND=$(echo "$TEST_OUTPUT" | grep "Expected categories found:" | grep -oP '\d+ out of \d+' | head -1)
    
    if [ $TEST_RESULT -eq 0 ]; then
        echo "SUCCESS"
        echo "  Total mappings: $TOTAL_MAPPINGS"
        echo "  Categories: $CATEGORIES_FOUND"
        echo "$MODEL_NAME - Mappings: $TOTAL_MAPPINGS, Categories: $CATEGORIES_FOUND" >> $RESULTS_FILE
    else
        echo "FAILED"
        echo "  Test execution failed - check logs for details"
        echo "$MODEL_NAME - FAILED TO RUN" >> $RESULTS_FILE
    fi
    sleep 2
done

echo ""
echo "=================================================================================================="

echo ""
echo "=================================================================================================="
echo "COMPARISON SUMMARY"
echo "=================================================================================================="
cat $RESULTS_FILE
echo ""
echo "Detailed results saved to: $RESULTS_FILE"
echo "=================================================================================================="
