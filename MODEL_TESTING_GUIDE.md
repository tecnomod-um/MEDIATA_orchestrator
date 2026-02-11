# Embedding Model Comparison Guide

This guide explains how to test different embedding models to find the best one for medical data mapping.

## Current Baseline

**Model:** sentence-transformers/all-MiniLM-L6-v2  
**Results:**
- ✅ 27 mappings created
- ✅ 6/7 key categories identified (sex, bath, type, feed, groom, stair)  
- ✅ 25/25 value mappings valid
- ✅ Execution time: ~12 seconds

**Keys identified:** dress, stair, type, bath, sex, bowel, bladder, feed, groom, toilet, transfer, etc.

## How to Test Different Models

### Step 1: Update application.properties

Edit `src/main/resources/application.properties` and change the model URIs:

```properties
# Replace with model you want to test
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/MODEL-NAME
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/MODEL-NAME
```

### Step 2: Run the test

```bash
mvn -Dtest=MappingServiceReportTest test
```

### Step 3: Check results

Look for these key metrics in the output:
```
Total mappings found: XX
Expected categories found: X out of 7
Valid mappings: XX
```

### Step 4: Compare with baseline

Good model characteristics:
- ✅ Total mappings: 25-30 (more is better, but too many may be false positives)
- ✅ Categories found: 6-7 out of 7
- ✅ All value mappings should be valid
- ✅ Keys should include: sex, bath, type, feed, groom, stair, toilet/bladder/bowel

## Recommended Models to Test

### General-Purpose Models

**1. sentence-transformers/all-mpnet-base-v2** (RECOMMENDED TO TRY)
- Larger model (768 dimensions vs 384)
- Generally better quality than all-MiniLM-L6-v2
- Slower but more accurate

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-mpnet-base-v2
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-mpnet-base-v2
```

**2. sentence-transformers/paraphrase-MiniLM-L6-v2**
- Optimized for paraphrase detection
- Good for finding similar meanings (e.g., "toileting" and "toilet use")

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/paraphrase-MiniLM-L6-v2
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/paraphrase-MiniLM-L6-v2
```

**3. sentence-transformers/all-MiniLM-L12-v2**
- Slightly larger than L6 (12 layers vs 6)
- Better quality with minimal speed impact

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L12-v2
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/sentence-transformers/all-MiniLM-L12-v2
```

### Clinical/Medical Models

**Note:** Clinical models may not be available via DJL ONNX Hub. If they fail to load, they likely don't have ONNX versions available.

**4. microsoft/BiomedNLP-PubMedBERT-base-uncased-abstract**
- Trained on PubMed abstracts
- Specialized for biomedical terminology

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/microsoft/BiomedNLP-PubMedBERT-base-uncased-abstract
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/microsoft/BiomedNLP-PubMedBERT-base-uncased-abstract
```

**5. emilyalsentzer/Bio_ClinicalBERT**
- Trained on clinical notes (MIMIC-III)
- Best for clinical terminology

```properties
spring.ai.embedding.transformer.onnx.modelUri=djl://ai.djl.huggingface.onnx/emilyalsentzer/Bio_ClinicalBERT
spring.ai.embedding.transformer.tokenizer.uri=djl://ai.djl.huggingface.onnx/emilyalsentzer/Bio_ClinicalBERT
```

## Models to AVOID

❌ **intfloat/e5-small-v2**  
Caused production issues - merged all 67 clusters into 1. Not suitable for this task.

## Evaluation Criteria

When comparing models, prioritize in this order:

1. **Value mapping correctness** (must be 100%)
   - FIM 1-7 must map to Barthel 0-10 ranges correctly
   - Categorical values must match semantically

2. **Category identification** (target: 6-7 out of 7)
   - Must identify: sex, bath, type, feed, groom, stair, toilet

3. **Total mappings** (target: 25-30)
   - Too few (<20): Missing important mappings
   - Too many (>35): Likely creating false positives

4. **Execution time** (<30 seconds acceptable)
   - Faster is better for production use

## Testing Protocol

For a comprehensive comparison:

1. Test baseline (all-MiniLM-L6-v2) - record results
2. Test each candidate model - record results
3. Compare metrics side-by-side
4. Choose model with best combination of:
   - Highest categories identified
   - Most mappings in 25-30 range
   - 100% value mapping correctness
   - Acceptable speed

## Example Comparison Table

| Model | Mappings | Categories | Value Accuracy | Time |
|-------|----------|------------|----------------|------|
| all-MiniLM-L6-v2 | 27 | 6/7 | 25/25 ✅ | 12s |
| all-mpnet-base-v2 | ? | ? | ? | ? |
| paraphrase-MiniLM-L6-v2 | ? | ? | ? | ? |
| all-MiniLM-L12-v2 | ? | ? | ? | ? |

Fill in the table as you test each model, then choose the winner!

## Production Deployment

Once you've chosen the best model:

1. Update `src/main/resources/application.properties` with winning model URIs
2. Update `src/main/resources/application-docker.properties` with same URIs
3. Run full test suite: `mvn test`
4. Rebuild: `mvn clean package`
5. Deploy to production
6. Monitor first few API calls to confirm quality

## Troubleshooting

**Model fails to load:**
- Model may not have ONNX version available
- Check DJL hub: https://djl.ai/
- Try different model

**Performance degraded:**
- Check similarity threshold in code (currently 0.56)
- May need to adjust for different models

**Wrong mappings:**
- Model may not be suitable for this domain
- Try medical-specific models
- Revert to baseline if issues persist

## Notes

- Models are cached in `${java.io.tmpdir}/spring-ai-onnx-model`
- First run downloads model (~50-200MB depending on model)
- Subsequent runs use cached version
- Clear cache if you suspect corruption: `rm -rf /tmp/spring-ai-onnx-model`
