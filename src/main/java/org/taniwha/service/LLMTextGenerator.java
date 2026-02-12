package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Service for generating natural language descriptions using actual LLM text generation.
 * Uses Ollama (or other ChatModel) to generate contextual descriptions - NO hardcoded text.
 */
@Service
public class LLMTextGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(LLMTextGenerator.class);
    
    private final ChatModel chatModel;
    private final boolean llmEnabled;
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();
    
    @Autowired(required = false)
    public LLMTextGenerator(@Autowired(required = false) ChatModel chatModel,
                           @Value("${llm.enabled:true}") boolean llmEnabled) {
        this.chatModel = chatModel;
        this.llmEnabled = llmEnabled && chatModel != null;
        logger.info("[LLMTextGenerator] Initialized. LLM enabled: {}, ChatModel available: {}", 
                   llmEnabled, chatModel != null);
    }
    
    /**
     * Generate a medical/clinical description for a column using ACTUAL text generation.
     * Uses all context: column name + SNOMED terminology + sample values.
     * Generates real text via LLM - NO hardcoding.
     */
    public String generateColumnDescription(String columnName, String terminology, List<String> sampleValues) {
        String cacheKey = "col:" + columnName + ":" + terminology + ":" + (sampleValues != null ? sampleValues.hashCode() : 0);
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled) {
                logger.debug("[LLMTextGenerator] LLM disabled, returning column name");
                return columnName;
            }
            
            try {
                // Build rich context prompt for text generation
                StringBuilder prompt = new StringBuilder();
                prompt.append("Generate a brief medical description (1-2 sentences) for a clinical data field.\n\n");
                prompt.append("Field name: ").append(columnName).append("\n");
                
                if (terminology != null && !terminology.isEmpty() && !terminology.startsWith("CONCEPT_")) {
                    String term = extractTermFromSnomed(terminology);
                    if (!term.isEmpty()) {
                        prompt.append("SNOMED CT terminology: ").append(term).append("\n");
                    }
                }
                
                if (sampleValues != null && !sampleValues.isEmpty()) {
                    int limit = Math.min(5, sampleValues.size());
                    prompt.append("Example values: ").append(String.join(", ", sampleValues.subList(0, limit)));
                    if (sampleValues.size() > limit) {
                        prompt.append("...");
                    }
                    prompt.append("\n");
                }
                
                prompt.append("\nDescribe what this field represents in a healthcare/clinical context. ");
                prompt.append("Focus on what it measures or represents, not how it's calculated. ");
                prompt.append("Be concise and professional.\n\nDescription:");
                
                // Generate text using LLM
                String response = callLLM(prompt.toString());
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
     * Generate a description for a numeric value using ACTUAL text generation.
     * Uses all context: column name + value + range position.
     * LLM understands context and generates appropriate text (e.g., "complete dependence" for low scores).
     */
    public String generateNumericValueDescription(String columnName, String value, String min, String max) {
        String cacheKey = "numval:" + columnName + ":" + value + ":" + min + ":" + max;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled) {
                return value;
            }
            
            try {
                // Calculate relative position for context
                double position = calculateRelativePosition(value, min, max);
                String positionDesc;
                if (position < 0.33) {
                    positionDesc = "low end";
                } else if (position < 0.67) {
                    positionDesc = "middle";
                } else {
                    positionDesc = "high end";
                }
                
                // Build rich context prompt
                StringBuilder prompt = new StringBuilder();
                prompt.append("Generate a brief clinical description (1 sentence) for a specific score.\n\n");
                prompt.append("Assessment: ").append(columnName).append("\n");
                prompt.append("Score: ").append(value);
                if (min != null && max != null) {
                    prompt.append(" (out of range ").append(min).append("-").append(max).append(", ");
                    prompt.append("at ").append(positionDesc).append(")");
                }
                prompt.append("\n\n");
                prompt.append("Describe what this score means clinically. ");
                prompt.append("For functional assessments, indicate the level of independence, function, or ability. ");
                prompt.append("Be specific about what this score represents (e.g., complete dependence, needs assistance, or independence). ");
                prompt.append("Be concise and professional.\n\nDescription:");
                
                // Generate text using LLM
                String response = callLLM(prompt.toString());
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
     * Generate a description for a categorical value using ACTUAL text generation.
     * Uses context: column name + value to generate appropriate description.
     */
    public String generateCategoricalValueDescription(String columnName, String value) {
        String cacheKey = "catval:" + columnName + ":" + value;
        return cache.computeIfAbsent(cacheKey, k -> {
            if (!llmEnabled) {
                return value;
            }
            
            try {
                // Build context prompt
                StringBuilder prompt = new StringBuilder();
                prompt.append("Generate a brief clinical description (1 sentence) for a categorical value.\n\n");
                prompt.append("Field: ").append(columnName).append("\n");
                prompt.append("Value: ").append(value).append("\n\n");
                prompt.append("Describe what this category means in a medical/clinical context. ");
                prompt.append("Explain what this value represents. ");
                prompt.append("Be specific and professional.\n\nDescription:");
                
                // Generate text using LLM
                String response = callLLM(prompt.toString());
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
     * Call the LLM to generate text based on prompt.
     * Returns the generated text.
     */
    private String callLLM(String promptText) {
        try {
            UserMessage userMessage = new UserMessage(promptText);
            Prompt prompt = new Prompt(userMessage);
            
            ChatResponse response = chatModel.call(prompt);
            
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                return response.getResult().getOutput().getText();
            }
            
            logger.warn("[LLMTextGenerator] Empty response from LLM");
            return "";
            
        } catch (Exception e) {
            logger.warn("[LLMTextGenerator] LLM call failed: {}", e.getMessage());
            throw e;
        }
    }
    
    /**
     * Calculate relative position of value in min-max range (0.0 to 1.0).
     */
    private double calculateRelativePosition(String value, String min, String max) {
        try {
            double val = Double.parseDouble(value);
            double minVal = Double.parseDouble(min);
            double maxVal = Double.parseDouble(max);
            
            if (maxVal == minVal) return 0.5;
            
            double position = (val - minVal) / (maxVal - minVal);
            return Math.max(0.0, Math.min(1.0, position));
            
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }
    
    /**
     * Extract term from SNOMED format: "code|term|" → "term"
     */
    private String extractTermFromSnomed(String snomed) {
        if (snomed == null || snomed.isEmpty()) return "";
        
        if (snomed.contains("|")) {
            String[] parts = snomed.split("\\|");
            if (parts.length > 1) {
                return parts[1].trim();
            }
        }
        return snomed;
    }
    
    /**
     * Clean up LLM response - remove quotes, trim, remove preambles.
     */
    private String cleanResponse(String response) {
        if (response == null) return "";
        
        String cleaned = response.trim();
        
        // Remove surrounding quotes
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        
        // Remove common preambles from LLM responses
        cleaned = cleaned.replaceFirst("^(This field represents|This value represents|This indicates|This means|Description:)\\s+", "");
        cleaned = cleaned.replaceFirst("^(In this context,|Clinically,|Medically,)\\s+", "");
        
        // Capitalize first letter
        if (!cleaned.isEmpty()) {
            cleaned = cleaned.substring(0, 1).toUpperCase() + cleaned.substring(1);
        }
        
        // Ensure it ends with period if it doesn't have punctuation
        if (!cleaned.isEmpty() && !cleaned.matches(".*[.!?]$")) {
            cleaned += ".";
        }
        
        return cleaned;
    }
    
    /**
     * Clear the description cache.
     */
    public void clearCache() {
        cache.clear();
    }
}
