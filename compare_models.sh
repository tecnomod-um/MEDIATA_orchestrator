#!/bin/bash

echo "=================================================================================================="
echo "EMBEDDING MODEL COMPARISON TEST"
echo "=================================================================================================="
echo ""

cp src/main/resources/application.properties /tmp/application.properties.backup

declare -a MODELS=(
  "all-MiniLM-L6-v2 (BASELINE)|djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L6-v2|Current production model"
  "all-mpnet-base-v2|djl://ai.djl.huggingface.onnx/sentence-transformers/all-mpnet-base-v2|Larger, potentially better quality"
  "paraphrase-MiniLM-L6-v2|djl://ai.djl.huggingface.onnx/sentence-transformers/paraphrase-MiniLM-L6-v2|Optimized for paraphrase detection"
  "all-MiniLM-L12-v2|djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L12-v2|Larger MiniLM with more layers"
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
    echo "URI: $MODEL_URI"
    echo "=================================================================================================="
    
    # Update application.properties with this model
    cat > src/main/resources/application.properties << EOF
# Temporarily updated for model comparison test
spring.ai.embedding.transformer.onnx.modelUri=$MODEL_URI
spring.ai.embedding.transformer.tokenizer.uri=$MODEL_URI
spring.ai.embedding.transformer.cache.enabled=true
spring.ai.embedding.transformer.cache.directory=\${java.io.tmpdir}/spring-ai-onnx-model
logging.level.org.taniwha.service.MappingService=INFO
EOF
    
    echo "Updated application.properties with model: $MODEL_NAME"
    echo "Running MappingServiceReportTest..."
    TEST_OUTPUT=$(mvn -Dtest=MappingServiceReportTest test 2>&1)
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
echo "Restoring original application.properties..."
mv /tmp/application.properties.backup src/main/resources/application.properties
echo "=================================================================================================="

echo ""
echo "=================================================================================================="
echo "COMPARISON SUMMARY"
echo "=================================================================================================="
cat $RESULTS_FILE
echo ""
echo "Detailed results saved to: $RESULTS_FILE"
echo "=================================================================================================="
