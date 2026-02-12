package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for generating natural language text using LLM.
 * Uses Ollama ChatModel for text generation.
 */
@Service
public class LLMTextGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMTextGenerator.class);
    
    private final ChatModel chatModel;
    private final boolean llmEnabled;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    
    @Autowired(required = false)
    public LLMTextGenerator(ChatModel chatModel,
                           @Value("${llm.enabled:false}") boolean llmEnabled) {
        this.chatModel = chatModel;
        this.llmEnabled = llmEnabled;
        logger.info("[LLMTextGenerator] Initialized. LLM enabled: {}, ChatModel available: {}", 
                   llmEnabled, chatModel != null);
    }
    
    /**
     * Generate a description for a column/field.
     */
    public String generateColumnDescription(String columnName, String values) {
        String cacheKey = "col:" + columnName + ":" + values;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled || chatModel == null) {
                return generateFallbackColumnDescription(columnName, values);
            }
            
            try {
                String promptText = "Generate a brief, clear medical/clinical description (1-2 sentences) " +
                                  "for a data field named '{columnName}' with possible values: {values}. " +
                                  "Describe what this field represents in a medical or healthcare context. " +
                                  "Be concise and professional.";
                
                Map<String, Object> params = new HashMap<>();
                params.put("columnName", columnName);
                params.put("values", values != null ? values : "various values");
                
                PromptTemplate template = new PromptTemplate(promptText);
                Prompt prompt = template.create(params);
                
                String response = chatModel.call(prompt).getResult().getOutput().getContent();
                String cleaned = response.trim();
                
                logger.debug("[LLMTextGenerator] Generated column description for '{}': {}", columnName, cleaned);
                return cleaned;
                
            } catch (Exception e) {
                logger.warn("[LLMTextGenerator] Failed to generate column description for '{}': {}", 
                           columnName, e.getMessage());
                return generateFallbackColumnDescription(columnName, values);
            }
        });
    }
    
    /**
     * Generate a description for a specific value in context of its column.
     */
    public String generateValueDescription(String columnName, String value, String context) {
        String cacheKey = "val:" + columnName + ":" + value + ":" + context;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled || chatModel == null) {
                return generateFallbackValueDescription(columnName, value, context);
            }
            
            try {
                String promptText = "In the medical/clinical context of '{columnName}', " +
                                  "generate a brief description (1 sentence) of what the value '{value}' represents. " +
                                  "{context} " +
                                  "Be specific about what this value means clinically. Be concise and professional.";
                
                Map<String, Object> params = new HashMap<>();
                params.put("columnName", columnName);
                params.put("value", value);
                params.put("context", context != null ? context : "");
                
                PromptTemplate template = new PromptTemplate(promptText);
                Prompt prompt = template.create(params);
                
                String response = chatModel.call(prompt).getResult().getOutput().getContent();
                String cleaned = response.trim();
                
                logger.debug("[LLMTextGenerator] Generated value description for '{}' = '{}': {}", 
                            columnName, value, cleaned);
                return cleaned;
                
            } catch (Exception e) {
                logger.warn("[LLMTextGenerator] Failed to generate value description for '{}' = '{}': {}", 
                           columnName, value, e.getMessage());
                return generateFallbackValueDescription(columnName, value, context);
            }
        });
    }
    
    /**
     * Generate a description for a numeric value in a range.
     */
    public String generateNumericValueDescription(String columnName, String value, 
                                                  double minValue, double maxValue, double normalizedPosition) {
        String cacheKey = "num:" + columnName + ":" + value + ":" + normalizedPosition;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled || chatModel == null) {
                return generateFallbackNumericDescription(columnName, value, normalizedPosition);
            }
            
            try {
                String positionDesc;
                if (normalizedPosition < 0.33) {
                    positionDesc = "This is a low score in the range.";
                } else if (normalizedPosition < 0.67) {
                    positionDesc = "This is a mid-range score.";
                } else {
                    positionDesc = "This is a high score in the range.";
                }
                
                String promptText = "In the medical/clinical assessment '{columnName}' (range {min} to {max}), " +
                                  "generate a brief description (1 sentence) of what a score of {value} represents. " +
                                  "{positionDesc} " +
                                  "Describe what this score means in terms of patient status, ability, or condition. " +
                                  "Be specific and professional.";
                
                Map<String, Object> params = new HashMap<>();
                params.put("columnName", columnName);
                params.put("value", value);
                params.put("min", String.valueOf((int)minValue));
                params.put("max", String.valueOf((int)maxValue));
                params.put("positionDesc", positionDesc);
                
                PromptTemplate template = new PromptTemplate(promptText);
                Prompt prompt = template.create(params);
                
                String response = chatModel.call(prompt).getResult().getOutput().getContent();
                String cleaned = response.trim();
                
                logger.debug("[LLMTextGenerator] Generated numeric description for '{}' = {}: {}", 
                            columnName, value, cleaned);
                return cleaned;
                
            } catch (Exception e) {
                logger.warn("[LLMTextGenerator] Failed to generate numeric description for '{}' = {}: {}", 
                           columnName, value, e.getMessage());
                return generateFallbackNumericDescription(columnName, value, normalizedPosition);
            }
        });
    }
    
    // Fallback methods for when LLM is unavailable
    
    private String generateFallbackColumnDescription(String columnName, String values) {
        return String.format("Data field '%s' with values: %s", 
                           columnName, values != null ? values : "various values");
    }
    
    private String generateFallbackValueDescription(String columnName, String value, String context) {
        return String.format("Value '%s' in %s", value, columnName);
    }
    
    private String generateFallbackNumericDescription(String columnName, String value, double position) {
        if (position < 0.33) {
            return String.format("Low score of %s in %s indicating limited ability", value, columnName);
        } else if (position < 0.67) {
            return String.format("Mid-range score of %s in %s indicating moderate ability", value, columnName);
        } else {
            return String.format("High score of %s in %s indicating good ability", value, columnName);
        }
    }
    
    public void clearCache() {
        cache.clear();
        logger.info("[LLMTextGenerator] Cache cleared");
    }
}
