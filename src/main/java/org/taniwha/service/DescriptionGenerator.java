package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates human-readable descriptions for columns and values using semantic embeddings.
 */
@Service
public class DescriptionGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(DescriptionGenerator.class);
    
    private final EmbeddingsClient embeddingsClient;
    private final Map<String, String> descriptionCache = new ConcurrentHashMap<>();
    
    // Medical domain templates for smart description generation
    private static final Map<String, String> COLUMN_TEMPLATES = new HashMap<>();
    private static final Map<String, List<String>> DEPENDENCE_TEMPLATES = new HashMap<>();
    
    static {
        // Column-level templates
        COLUMN_TEMPLATES.put("barthel", "Assessment of {activity} function and independence in activities of daily living");
        COLUMN_TEMPLATES.put("fim", "Functional Independence Measure for {activity} ability and self-care");
        COLUMN_TEMPLATES.put("nihss", "NIH Stroke Scale assessment of neurological function");
        COLUMN_TEMPLATES.put("type", "Classification or type of {condition}");
        COLUMN_TEMPLATES.put("etiology", "Underlying cause or origin of the medical condition");
        COLUMN_TEMPLATES.put("age", "Age at time of assessment or event");
        COLUMN_TEMPLATES.put("sex", "Biological sex or gender");
        COLUMN_TEMPLATES.put("gender", "Gender identity");
        
        // Value dependence level descriptions (low to high scores)
        DEPENDENCE_TEMPLATES.put("barthel", Arrays.asList(
            "Complete dependence; requires full assistance with all aspects",
            "Major dependence; requires substantial assistance",
            "Moderate dependence; requires moderate assistance",
            "Partial independence; requires some assistance",
            "Mostly independent; requires minimal assistance",
            "Independent; no assistance required"
        ));
        
        DEPENDENCE_TEMPLATES.put("fim", Arrays.asList(
            "Total assistance required; patient performs less than 25% of effort",
            "Maximal assistance required; patient performs 25-49% of effort",
            "Moderate assistance required; patient performs 50-74% of effort",
            "Minimal assistance or supervision required; patient performs 75% or more",
            "Modified independence; requires adaptive equipment or extra time",
            "Complete independence; no assistance or devices needed"
        ));
    }
    
    @Autowired
    public DescriptionGenerator(EmbeddingsClient embeddingsClient) {
        this.embeddingsClient = embeddingsClient;
    }
    
    /**
     * Generate description for a column based on its name and context
     */
    public String generateColumnDescription(String columnName, List<String> values) {
        String cacheKey = "col:" + columnName;
        return descriptionCache.computeIfAbsent(cacheKey, k -> {
            try {
                String lower = columnName.toLowerCase();
                
                // Try to match with template based on semantic similarity
                String bestMatch = findBestTemplateMatch(lower, COLUMN_TEMPLATES.keySet());
                if (bestMatch != null) {
                    String template = COLUMN_TEMPLATES.get(bestMatch);
                    String activity = extractActivity(columnName);
                    String condition = extractCondition(columnName, values);
                    return template.replace("{activity}", activity)
                                 .replace("{condition}", condition);
                }
                
                // Generate generic description based on column type
                if (values != null && !values.isEmpty()) {
                    return "Assessment or measurement of " + formatColumnName(columnName);
                }
                
                return "Clinical or demographic data field: " + formatColumnName(columnName);
            } catch (Exception e) {
                logger.warn("Failed to generate column description for '{}': {}", columnName, e.getMessage());
                return formatColumnName(columnName);
            }
        });
    }
    
    /**
     * Generate description for a specific value in the context of a column
     */
    public String generateValueDescription(String columnName, String value, String min, String max) {
        String cacheKey = "val:" + columnName + ":" + value;
        return descriptionCache.computeIfAbsent(cacheKey, k -> {
            try {
                String lower = columnName.toLowerCase();
                
                // Handle numeric values with range context
                if (min != null && max != null) {
                    return generateNumericDescription(columnName, value, min, max);
                }
                
                // Handle categorical values
                return generateCategoricalDescription(columnName, value);
            } catch (Exception e) {
                logger.warn("Failed to generate value description for '{}' in '{}': {}", value, columnName, e.getMessage());
                return value;
            }
        });
    }
    
    private String generateNumericDescription(String columnName, String value, String min, String max) {
        try {
            double numValue = Double.parseDouble(value);
            double minValue = Double.parseDouble(min);
            double maxValue = Double.parseDouble(max);
            
            // Calculate position in range (0.0 to 1.0)
            double position = (numValue - minValue) / (maxValue - minValue);
            
            String lower = columnName.toLowerCase();
            
            // Use assessment-specific templates for Barthel/FIM
            String assessmentType = null;
            if (lower.contains("barthel") || lower.contains("bart")) {
                assessmentType = "barthel";
            } else if (lower.contains("fim")) {
                assessmentType = "fim";
            }
            
            if (assessmentType != null && DEPENDENCE_TEMPLATES.containsKey(assessmentType)) {
                List<String> templates = DEPENDENCE_TEMPLATES.get(assessmentType);
                int index = (int)(position * (templates.size() - 1));
                index = Math.max(0, Math.min(templates.size() - 1, index));
                return templates.get(index);
            }
            
            // Generic numeric description based on position
            String activity = extractActivity(columnName);
            if (position <= 0.33) {
                return "Low " + activity + " score; severe limitation or dependence";
            } else if (position <= 0.67) {
                return "Moderate " + activity + " score; partial limitation or assistance needed";
            } else {
                return "High " + activity + " score; good function or independence";
            }
        } catch (NumberFormatException e) {
            return value;
        }
    }
    
    private String generateCategoricalDescription(String columnName, String value) {
        String lower = value.toLowerCase();
        String colLower = columnName.toLowerCase();
        
        // Medical condition types
        if (colLower.contains("type") || colLower.contains("etiology")) {
            if (lower.contains("isch")) {
                return "Ischemic type; caused by blockage of blood flow";
            } else if (lower.contains("hem")) {
                return "Hemorrhagic type; caused by bleeding";
            }
        }
        
        // Sex/Gender
        if (colLower.contains("sex") || colLower.contains("gender")) {
            if (lower.equals("m") || lower.equals("male") || lower.equals("h")) {
                return "Male";
            } else if (lower.equals("f") || lower.equals("female") || lower.equals("d")) {
                return "Female";
            }
        }
        
        // Yes/No values
        if (lower.equals("y") || lower.equals("yes")) {
            return "Present or positive for " + formatColumnName(columnName);
        } else if (lower.equals("n") || lower.equals("no")) {
            return "Absent or negative for " + formatColumnName(columnName);
        }
        
        // Generic categorical
        return value + " category in " + formatColumnName(columnName);
    }
    
    private String findBestTemplateMatch(String columnName, Set<String> templates) {
        if (embeddingsClient == null) return null;
        
        try {
            float[] queryEmbed = embeddingsClient.embed(columnName);
            double maxSim = 0.5; // Minimum similarity threshold
            String bestMatch = null;
            
            for (String template : templates) {
                float[] templateEmbed = embeddingsClient.embed(template);
                double sim = cosineSimilarity(queryEmbed, templateEmbed);
                if (sim > maxSim) {
                    maxSim = sim;
                    bestMatch = template;
                }
            }
            
            return bestMatch;
        } catch (Exception e) {
            return null;
        }
    }
    
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    private String extractActivity(String columnName) {
        String lower = columnName.toLowerCase();
        if (lower.contains("toilet")) return "toileting";
        if (lower.contains("bath")) return "bathing";
        if (lower.contains("dress")) return "dressing";
        if (lower.contains("feed") || lower.contains("eat")) return "feeding";
        if (lower.contains("groom")) return "grooming";
        if (lower.contains("stair")) return "stair climbing";
        if (lower.contains("transfer")) return "transferring";
        if (lower.contains("mobil") || lower.contains("walk")) return "mobility";
        if (lower.contains("bowel")) return "bowel control";
        if (lower.contains("bladder")) return "bladder control";
        return formatColumnName(columnName);
    }
    
    private String extractCondition(String columnName, List<String> values) {
        if (values != null && !values.isEmpty()) {
            String firstValue = values.get(0).toLowerCase();
            if (firstValue.contains("stroke") || firstValue.contains("isch") || firstValue.contains("hem")) {
                return "stroke or cerebrovascular event";
            }
            if (firstValue.contains("diabet")) {
                return "diabetes";
            }
        }
        return "medical condition";
    }
    
    private String formatColumnName(String columnName) {
        // Convert camelCase or snake_case to readable format
        return columnName.replaceAll("([a-z])([A-Z])", "$1 $2")
                        .replaceAll("_", " ")
                        .toLowerCase();
    }
    
    /**
     * Clear the description cache
     */
    public void clearCache() {
        descriptionCache.clear();
    }
}
