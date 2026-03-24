package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingsWarmupConfig {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingsWarmupConfig.class);

    @Bean
    ApplicationRunner warmupEmbeddings(EmbeddingModel embeddingModel) {
        return args -> {
            try {
                logger.info("[EmbeddingsWarmup] Starting warmup...");
                float[] result = embeddingModel.embed("warmup");
                logger.info("[EmbeddingsWarmup] SUCCESS! Embedding dimension: {}", result.length);
            } catch (Exception e) {
                logger.error("[EmbeddingsWarmup] FAILED to warmup embedding model: {}", e.getMessage(), e);
                logger.error("[EmbeddingsWarmup] The application will continue but embeddings may not work!");
            }
        };
    }
}
