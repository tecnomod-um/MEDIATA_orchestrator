package org.taniwha.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmbeddingsClient {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EmbeddingsClient.class);

    private final EmbeddingModel embeddingModel;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();
    private volatile int embeddingDimension = -1;
    private volatile boolean modelFailed = false;

    public EmbeddingsClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        
        // Try to initialize and get embedding dimension
        try {
            float[] test = embeddingModel.embed("test");
            embeddingDimension = test.length;
            logger.info("[EmbeddingsClient] Initialized successfully. Embedding dimension: {}", embeddingDimension);
        } catch (Exception e) {
            logger.error("[EmbeddingsClient] FAILED to initialize embedding model: {}", e.getMessage(), e);
            modelFailed = true;
            embeddingDimension = 384;
        }
    }

    public float[] embed(String text) {
        if (modelFailed) {
            logger.warn("[EmbeddingsClient] Model failed during initialization. Returning zero vector.");
            return new float[embeddingDimension];
        }
        
        String key = (text == null) ? "" : text.trim();
        return cache.computeIfAbsent(key, k -> {
            try {
                float[] out = embeddingModel.embed(k);
                l2NormalizeInPlace(out);
                return out;
            } catch (Exception e) {
                logger.error("[EmbeddingsClient] Error embedding text '{}': {}", 
                    k.length() > 50 ? k.substring(0, 50) + "..." : k, e.getMessage());
                modelFailed = true;
                return new float[embeddingDimension];
            }
        });
    }

    private static void l2NormalizeInPlace(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += (double) x * (double) x;
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) return;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
    }
}
