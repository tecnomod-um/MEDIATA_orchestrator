package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for generating natural language text using LLM (OpenAI via Spring AI).
 * Generates descriptions for medical/clinical data fields and values.
 */
@Service
public class LLMTextGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMTextGenerator.class);
    
    private final ChatClient chatClient;
    private final boolean llmEnabled;
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();
    
    @Autowired(required = false)
    public LLMTextGenerator(@Autowired(required = false) ChatModel chatModel,
                           @Value("${llm.enabled:true}") boolean llmEnabled) {
        this.chatClient = chatModel != null ? ChatClient.create(chatModel) : null;
        this.llmEnabled = llmEnabled && chatModel != null;
        logger.info("[LLMTextGenerator] Initialized. LLM enabled: {}, ChatModel available: {}", 
                   llmEnabled, chatModel != null);
    }
    
    /**
     * Generate a medical/clinical description for a column/field.
     * Uses LLM to generate natural language based on column name, sample values, and SNOMED terminology.
     * 
     * @param columnName The name of the column
     * @param terminology SNOMED CT terminology code/description from Snowstorm (optional)
     * @param sampleValues Sample values from the column (optional)
     */
    public String generateColumnDescription(String columnName, String terminology, List<String> sampleValues) {
        String cacheKey = "col:" + columnName + ":" + terminology + ":" + (sampleValues != null ? sampleValues.hashCode() : 0);
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled) {
                logger.debug("[LLMTextGenerator] LLM disabled, returning column name as description");
                return columnName;
            }
            
            try {
                String valuesContext = "";
                if (sampleValues != null && !sampleValues.isEmpty()) {
                    int limit = Math.min(5, sampleValues.size());
                    valuesContext = " Possible values include: " + String.join(", ", sampleValues.subList(0, limit));
                    if (sampleValues.size() > limit) {
                        valuesContext += "...";
                    }
                }
                
                String terminologyContext = "";
                if (terminology != null && !terminology.isEmpty() && !terminology.startsWith("CONCEPT_")) {
                    // Parse SNOMED format: "code|term|" or just use as-is
                    String term = terminology;
                    if (terminology.contains("|")) {
                        String[] parts = terminology.split("\\|");
                        if (parts.length > 1) {
                            term = parts[1].trim();
                        }
                    }
                    terminologyContext = " SNOMED CT terminology: '" + term + "'.";
                }
                
                String prompt = String.format(
                    "Generate a brief, professional medical/clinical description (1-2 sentences maximum) " +
                    "for a data field named '%s'.%s%s " +
                    "Describe what this field represents in a healthcare context. " +
                    "Be concise and avoid technical jargon. Focus on what it measures or represents, not how it's calculated.",
                    columnName, terminologyContext, valuesContext
                );
                
                String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
                
                String cleaned = cleanResponse(response);
                logger.debug("[LLMTextGenerator] Generated column description for '{}': {}", columnName, cleaned);
                return cleaned;
                
            } catch (Exception e) {
                logger.warn("[LLMTextGenerator] Failed to generate column description for '{}': {}",
                           columnName, e.getMessage());
                return columnName;
            }
        });
    }
    
    /**
     * Generate a description for a specific value in the context of its column.
     * For numeric values with range context (min/max).
     */
    public String generateNumericValueDescription(String columnName, String value, String min, String max) {
        String cacheKey = "numval:" + columnName + ":" + value + ":" + min + ":" + max;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled) {
                return value;
            }
            
            try {
                String rangeContext = "";
                if (min != null && max != null) {
                    rangeContext = String.format(" The value range is from %s to %s.", min, max);
                }
                
                String prompt = String.format(
                    "In a medical/clinical context for the field '%s', what does a score/value of %s mean?%s " +
                    "Provide a brief description (1 sentence) of what this value represents clinically. " +
                    "For assessment scales, indicate the level of function, independence, or severity. " +
                    "Be specific and professional.",
                    columnName, value, rangeContext
                );
                
                String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
                
                String cleaned = cleanResponse(response);
                logger.debug("[LLMTextGenerator] Generated numeric value description for '{}' = '{}': {}",
                           columnName, value, cleaned);
                return cleaned;
                
            } catch (Exception e) {
                logger.warn("[LLMTextGenerator] Failed to generate numeric value description for '{}' = '{}': {}",
                           columnName, value, e.getMessage());
                return value;
            }
        });
    }
    
    /**
     * Generate a description for a categorical value in the context of its column.
     */
    public String generateCategoricalValueDescription(String columnName, String value) {
        String cacheKey = "catval:" + columnName + ":" + value;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled) {
                return value;
            }
            
            try {
                String prompt = String.format(
                    "In the medical/clinical context of '%s', what does the value '%s' represent? " +
                    "Provide a brief description (1 sentence) explaining what this category means. " +
                    "Be specific and professional.",
                    columnName, value
                );
                
                String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
                
                String cleaned = cleanResponse(response);
                logger.debug("[LLMTextGenerator] Generated categorical value description for '{}' = '{}': {}",
                           columnName, value, cleaned);
                return cleaned;
                
            } catch (Exception e) {
                logger.warn("[LLMTextGenerator] Failed to generate categorical value description for '{}' = '{}': {}",
                           columnName, value, e.getMessage());
                return value;
            }
        });
    }
    
    /**
     * Clean up LLM response - remove quotes, trim, remove preambles
     */
    private String cleanResponse(String response) {
        if (response == null) return "";
        
        String cleaned = response.trim();
        
        // Remove surrounding quotes if present
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        
        // Remove common preambles
        cleaned = cleaned.replaceFirst("^(This field represents|This value represents|This indicates|This means)\\s+", "");
        cleaned = cleaned.replaceFirst("^(In this context,|Clinically,|Medically,)\\s+", "");
        
        // Capitalize first letter
        if (!cleaned.isEmpty()) {
            cleaned = cleaned.substring(0, 1).toUpperCase() + cleaned.substring(1);
        }
        
        return cleaned;
    }
    
    /**
     * Clear the response cache
     */
    public void clearCache() {
        cache.clear();
    }
}
