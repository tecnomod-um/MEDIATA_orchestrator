package org.taniwha.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmbeddingsClient {

    private final EmbeddingModel embeddingModel;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();

    public EmbeddingsClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        String key = (text == null) ? "" : text.trim();
        return cache.computeIfAbsent(key, k -> {
            float[] out = embeddingModel.embed(k);
            l2NormalizeInPlace(out);
            return out;
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
