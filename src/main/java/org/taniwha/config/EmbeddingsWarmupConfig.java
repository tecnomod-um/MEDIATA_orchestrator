package org.taniwha.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingsWarmupConfig {

    @Bean
    ApplicationRunner warmupEmbeddings(EmbeddingModel embeddingModel) {
        return args -> {
            embeddingModel.embed("warmup");
        };
    }
}
