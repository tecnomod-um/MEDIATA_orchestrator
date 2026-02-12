# Model Comparison Quick Reference

## Quick Start

### Option 1: Use Helper Script (Recommended)
```bash
# Switch to a different model
./switch-model.sh mpnet

# Run test to see results
mvn -Dtest=MappingServiceReportTest test

# Switch back to default
./switch-model.sh default
```

### Option 2: Manual Configuration
Edit `src/main/resources/application.properties`:
1. Comment out current model URIs (add `#` at start of line)
2. Uncomment desired model URIs (remove `#`)
3. Restart application or run tests

## Available Models

### 1. default (all-MiniLM-L6-v2) ⭐ RECOMMENDED
- **Status**: Currently active, proven working
- **Dimensions**: 384
- **Size**: 90 MB
- **Speed**: Fastest
- **Quality**: Excellent (27 mappings, 6/7 categories)
- **Configuration**: No URIs needed (Spring AI default)

### 2. mpnet (all-mpnet-base-v2)
- **Dimensions**: 768
- **Size**: 420 MB (4.6x larger)
- **Speed**: Slower (12 layers vs 6)
- **Quality**: Same as default in tests (27 mappings, 6/7 categories)
- **Use Case**: Worth testing if you want to try larger model
- **Configuration**: Uncomment OPTION 2 in application.properties

### 3. paraphrase (paraphrase-MiniLM-L6-v2)
- **Dimensions**: 384
- **Size**: 90 MB
- **Speed**: Fast
- **Quality**: Same as default in tests (27 mappings, 6/7 categories)
- **Use Case**: Specialized for paraphrase detection
- **Configuration**: Uncomment OPTION 3 in application.properties

### 4. l12 (all-MiniLM-L12-v2)
- **Dimensions**: 384
- **Size**: 120 MB
- **Speed**: Fast
- **Quality**: Same as default in tests (27 mappings, 6/7 categories)
- **Use Case**: Deeper network, more layers
- **Configuration**: Uncomment OPTION 4 in application.properties

## Testing Workflow

1. **Switch model**:
   ```bash
   ./switch-model.sh [model-name]
   ```

2. **Run test**:
   ```bash
   mvn -Dtest=MappingServiceReportTest test
   ```

3. **Check output for**:
   - Total mappings created (target: 25-30)
   - Categories found (target: 6-7/7)
   - Key mappings (sex, bath, type, feed, etc.)
   - Value mapping validity (must be 100%)

4. **Compare with baseline**:
   - Baseline: 27 mappings, 6/7 categories, 100% valid

## Notes

- **All tested models produced identical results** (27 mappings, same keys)
- The clustering algorithm reaches stable equilibrium regardless of embedding nuances
- **Default model (all-MiniLM-L6-v2) is optimal**: smallest, fastest, same quality
- Model files are cached in `${java.io.tmpdir}/spring-ai-onnx-model`
- First run with new model will download files (may take time)

## Troubleshooting

**Model fails to download?**
- Check internet connection
- Verify HuggingFace is accessible
- Try default model (no custom URIs needed)

**Application won't start?**
- Ensure only one model is uncommented
- Check both modelUri and tokenizer.uri are set together
- Switch back to default: `./switch-model.sh default`

**Different results than documented?**
- Ensure you're using same test: `MappingServiceReportTest`
- Check test uses correct fixture: `fixture-request.json`
- Verify model downloaded completely (check cache directory)
