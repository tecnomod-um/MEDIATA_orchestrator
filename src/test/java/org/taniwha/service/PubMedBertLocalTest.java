package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Quick test to verify PubMedBERT ONNX quantized model works with local files.
 */
public class PubMedBertLocalTest {

    @Test
    void testPubMedBertQuantizedModel() throws Exception {
        String modelPath = "/tmp/pubmedbert-test/model_quantized.onnx";
        String tokenizerPath = "/tmp/pubmedbert-test/tokenizer.json";

        // Skip if files not present
        if (!new java.io.File(modelPath).exists()) {
            System.out.println("SKIPPED: model not downloaded");
            return;
        }

        System.out.println("Loading PubMedBERT quantized ONNX model from local files...");
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setModelResource("file://" + modelPath);
        model.setTokenizerResource("file://" + tokenizerPath);
        model.afterPropertiesSet();
        System.out.println("Model loaded!");

        float[] e1 = model.embed("WBC");
        float[] e2 = model.embed("White Blood Cell Count");
        assertEquals(768, e1.length, "PubMedBERT should produce 768-dim embeddings");
        System.out.println("Embedding dim: " + e1.length);

        double dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < e1.length; i++) {
            dot += e1[i] * e2[i];
            n1 += e1[i] * e1[i];
            n2 += e2[i] * e2[i];
        }
        double sim = dot / Math.sqrt(n1 * n2);
        System.out.printf("WBC <-> White Blood Cell Count similarity: %.4f%n", sim);
        assertTrue(sim > 0.4, "PubMedBERT should recognize medical abbreviations");
        System.out.println("SUCCESS!");
    }
}
