package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Ollama ChatModel bean.
 * This provides the ChatModel bean that LLMTextGenerator needs for text generation.
 */
@Configuration
public class OllamaChatConfig {
    private static final Logger logger = LoggerFactory.getLogger(OllamaChatConfig.class);
    
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${spring.ai.ollama.chat.options.model:llama2}")
    private String ollamaModel;
    
    @Value("${spring.ai.ollama.chat.options.temperature:0.7}")
    private double temperature;
    
    @Bean
    @ConditionalOnProperty(name = "llm.enabled", havingValue = "true", matchIfMissing = true)
    public ChatModel chatModel() {
        logger.info("[OllamaChatConfig] Creating OllamaChatModel");
        logger.info("[OllamaChatConfig]   Base URL: {}", ollamaBaseUrl);
        logger.info("[OllamaChatConfig]   Model: {}", ollamaModel);
        logger.info("[OllamaChatConfig]   Temperature: {}", temperature);
        
        // Create OllamaApi to connect to Ollama
        OllamaApi api = OllamaApi.builder()
            .baseUrl(ollamaBaseUrl)
            .build();
        
        // Create options for the chat model
        org.springframework.ai.ollama.api.OllamaChatOptions options = 
            org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                .model(ollamaModel)
                .temperature(temperature)
                .build();
        
        // Create ToolCallingManager (required by Spring AI 1.1.2)
        org.springframework.ai.model.tool.ToolCallingManager toolCallingManager = 
            org.springframework.ai.model.tool.ToolCallingManager.builder().build();
        
        // Create ModelManagementOptions (required by Spring AI 1.1.2)
        org.springframework.ai.ollama.management.ModelManagementOptions modelManagementOptions =
            org.springframework.ai.ollama.management.ModelManagementOptions.builder().build();
        
        // Create OllamaChatModel with all required parameters
        OllamaChatModel chatModel = new OllamaChatModel(
            api,
            options,
            toolCallingManager,
            io.micrometer.observation.ObservationRegistry.NOOP,
            modelManagementOptions
        );
        
        logger.info("[OllamaChatConfig] OllamaChatModel created successfully");
        return chatModel;
    }
}
