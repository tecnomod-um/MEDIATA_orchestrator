#!/bin/bash

# Script to test multiple embedding models and compare results

MODELS=(
    "sentence-transformers/all-MiniLM-L6-v2:BASELINE (384 dim, 6 layers)"
    "sentence-transformers/all-mpnet-base-v2:LARGE (768 dim, 12 layers)"
    "sentence-transformers/paraphrase-MiniLM-L6-v2:PARAPHRASE (384 dim, specialized)"
    "sentence-transformers/all-MiniLM-L12-v2:DEEP (384 dim, 12 layers)"
)

RESULTS_FILE="model_comparison_results.txt"
echo "========================================" > $RESULTS_FILE
echo "EMBEDDING MODEL COMPARISON TEST RESULTS" >> $RESULTS_FILE
echo "========================================" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE
echo "Test Date: $(date)" >> $RESULTS_FILE
echo "" >> $RESULTS_FILE

PROPS_FILE="src/main/resources/application.properties"
BACKUP_FILE="src/main/resources/application.properties.backup"

# Backup original config
cp $PROPS_FILE $BACKUP_FILE

echo "Testing ${#MODELS[@]} models..."
echo ""

for MODEL_ENTRY in "${MODELS[@]}"; do
    IFS=':' read -r MODEL_PATH MODEL_NAME <<< "$MODEL_ENTRY"
    
    echo "========================================"
    echo "Testing: $MODEL_NAME"
    echo "Model: $MODEL_PATH"
    echo "========================================"
    
    # Update application.properties
    sed -i.tmp "s|^spring.ai.embedding.transformer.onnx.modelUri=.*|spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/$MODEL_PATH|" $PROPS_FILE
    sed -i.tmp "s|^spring.ai.embedding.transformer.tokenizer.uri=.*|spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/$MODEL_PATH|" $PROPS_FILE
    
    # Run test and capture output
    echo "Running test..."
    START_TIME=$(date +%s)
    
    TEST_OUTPUT=$(mvn -Dtest=MappingServiceReportTest test 2>&1)
    TEST_EXIT_CODE=$?
    
    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))
    
    # Extract results
    MAPPINGS=$(echo "$TEST_OUTPUT" | grep "Total mappings found:" | sed 's/.*: //')
    CATEGORIES=$(echo "$TEST_OUTPUT" | grep "Expected categories found:" | sed 's/.*: //' | sed 's/ out.*//')
    KEYS=$(echo "$TEST_OUTPUT" | grep "Mapping keys:" | sed 's/.*: //')
    
    # Save results
    echo "----------------------------------------" >> $RESULTS_FILE
    echo "Model: $MODEL_NAME" >> $RESULTS_FILE
    echo "Path: $MODEL_PATH" >> $RESULTS_FILE
    echo "----------------------------------------" >> $RESULTS_FILE
    
    if [ $TEST_EXIT_CODE -eq 0 ]; then
        echo "✅ SUCCESS" >> $RESULTS_FILE
        echo "Execution time: ${DURATION}s" >> $RESULTS_FILE
        echo "Total mappings: $MAPPINGS" >> $RESULTS_FILE
        echo "Categories found: $CATEGORIES/7" >> $RESULTS_FILE
        echo "Mapping keys: $KEYS" >> $RESULTS_FILE
        echo "" >> $RESULTS_FILE
        
        echo "✅ SUCCESS - $MAPPINGS mappings, $CATEGORIES/7 categories, ${DURATION}s"
    else
        echo "❌ FAILED" >> $RESULTS_FILE
        ERROR_MSG=$(echo "$TEST_OUTPUT" | grep -A5 "ERROR\|Exception\|Failed" | head -10)
        echo "Error: $ERROR_MSG" >> $RESULTS_FILE
        echo "" >> $RESULTS_FILE
        
        echo "❌ FAILED - See $RESULTS_FILE for details"
    fi
    
    echo ""
done

# Restore original config
mv $BACKUP_FILE $PROPS_FILE
rm -f $PROPS_FILE.tmp

echo "========================================" >> $RESULTS_FILE
echo "TEST COMPLETE" >> $RESULTS_FILE
echo "========================================" >> $RESULTS_FILE

echo "========================================"
echo "All tests complete!"
echo "Results saved to: $RESULTS_FILE"
echo "========================================"
echo ""
echo "Results summary:"
cat $RESULTS_FILE
