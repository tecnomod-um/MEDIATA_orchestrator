package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates natural language descriptions using semantic understanding without actual LLM calls.
 */
@Service
public class LLMTextGenerator {
    
    private static final Logger log = LoggerFactory.getLogger(LLMTextGenerator.class);
    
    private final EmbeddingsClient embeddingsClient;
    private final Map<String, String> cache = new HashMap<>();
    
    public LLMTextGenerator(EmbeddingsClient embeddingsClient) {
        this.embeddingsClient = embeddingsClient;
    }
    
    public String generateColumnDescription(String columnName, String values) {
        String cacheKey = "col:" + columnName + ":" + values;
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        
        String description = generateSmartColumnDescription(columnName, values);
        cache.put(cacheKey, description);
        return description;
    }
    
    public String generateValueDescription(String columnName, String value) {
        String cacheKey = "val:" + columnName + ":" + value;
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        
        String description = generateSmartValueDescription(columnName, value);
        cache.put(cacheKey, description);
        return description;
    }
    
    public String generateNumericValueDescription(String columnName, String value, double minValue, double maxValue) {
        String cacheKey = "num:" + columnName + ":" + value;
        if (cache.containsKey(cacheKey)) {
            return cache.get(cacheKey);
        }
        
        String description = generateSmartNumericDescription(columnName, value, minValue, maxValue);
        cache.put(cacheKey, description);
        return description;
    }
    
    private String generateSmartColumnDescription(String columnName, String values) {
        String normalizedName = columnName.toLowerCase();
        
        if (isAssessmentScale(normalizedName)) {
            return generateAssessmentDescription(columnName, normalizedName);
        }
        
        if (isClassification(normalizedName)) {
            return generateClassificationDescription(columnName);
        }
        
        if (isDemographic(normalizedName)) {
            return generateDemographicDescription(columnName);
        }
        
        return String.format("Measurement or assessment of %s", columnName.toLowerCase());
    }
    
    private String generateSmartValueDescription(String columnName, String value) {
        String normalizedColumn = columnName.toLowerCase();
        String normalizedValue = value.toLowerCase();
        
        if (normalizedColumn.contains("type") || normalizedColumn.contains("etiology")) {
            return generateConditionTypeDescription(value, normalizedValue);
        }
        
        if (isYesNo(normalizedValue)) {
            return normalizedValue.startsWith("y") ? "Present or positive" : "Absent or negative";
        }
        
        if (isLaterality(normalizedValue)) {
            return normalizedValue.equals("r") ? "Right side" : "Left side";
        }
        
        return String.format("Category: %s", value);
    }
    
    private String generateSmartNumericDescription(String columnName, String value, double minValue, double maxValue) {
        try {
            double numericValue = Double.parseDouble(value);
            double position = (numericValue - minValue) / (maxValue - minValue);
            
            String normalizedColumn = columnName.toLowerCase();
            
            if (isFunctionalAssessment(normalizedColumn)) {
                return generateFunctionalLevelDescription(position, columnName);
            }
            
            return generateGenericNumericDescription(position, columnName, value);
            
        } catch (NumberFormatException e) {
            return value;
        }
    }
    
    private boolean isAssessmentScale(String name) {
        return name.contains("barthel") || name.contains("fim") || 
               name.contains("scale") || name.contains("index") ||
               name.contains("score") || name.contains("assessment");
    }
    
    private boolean isClassification(String name) {
        return name.contains("type") || name.contains("class") || 
               name.contains("category") || name.contains("etiology");
    }
    
    private boolean isDemographic(String name) {
        return name.contains("age") || name.contains("sex") || name.contains("gender") ||
               name.contains("education") || name.contains("occupation");
    }
    
    private boolean isFunctionalAssessment(String name) {
        return name.contains("toilet") || name.contains("bath") || name.contains("dress") ||
               name.contains("feed") || name.contains("groom") || name.contains("mobility") ||
               name.contains("transfer") || name.contains("stair") || name.contains("locomotion") ||
               name.contains("bladder") || name.contains("bowel");
    }
    
    private boolean isYesNo(String value) {
        return value.equals("y") || value.equals("n") || 
               value.equals("yes") || value.equals("no");
    }
    
    private boolean isLaterality(String value) {
        return value.equals("r") || value.equals("l") || 
               value.equals("right") || value.equals("left");
    }
    
    private String generateAssessmentDescription(String columnName, String normalizedName) {
        if (normalizedName.contains("barthel")) {
            return String.format("Barthel Index assessment of %s", extractActivity(columnName));
        } else if (normalizedName.contains("fim")) {
            return String.format("Functional Independence Measure of %s", extractActivity(columnName));
        } else {
            return String.format("Clinical assessment scale for %s", columnName.toLowerCase());
        }
    }
    
    private String generateClassificationDescription(String columnName) {
        return String.format("Classification or category of %s", columnName.toLowerCase().replace("type", "").trim());
    }
    
    private String generateDemographicDescription(String columnName) {
        return String.format("Demographic information: %s", columnName.toLowerCase());
    }
    
    private String generateConditionTypeDescription(String value, String normalizedValue) {
        if (normalizedValue.contains("isch")) {
            return "Ischemic type: caused by blocked blood flow";
        } else if (normalizedValue.contains("hem")) {
            return "Hemorrhagic type: caused by bleeding";
        } else {
            return String.format("Type or classification: %s", value);
        }
    }
    
    private String generateFunctionalLevelDescription(double position, String columnName) {
        String activity = extractActivity(columnName);
        
        if (position < 0.33) {
            return String.format("Complete dependence in %s; requires full assistance", activity);
        } else if (position < 0.67) {
            return String.format("Partial independence in %s; requires some assistance", activity);
        } else {
            return String.format("Independent in %s; no assistance required", activity);
        }
    }
    
    private String generateGenericNumericDescription(double position, String columnName, String value) {
        if (position < 0.33) {
            return String.format("Low score (%s) for %s", value, columnName.toLowerCase());
        } else if (position < 0.67) {
            return String.format("Moderate score (%s) for %s", value, columnName.toLowerCase());
        } else {
            return String.format("High score (%s) for %s", value, columnName.toLowerCase());
        }
    }
    
    private String extractActivity(String columnName) {
        String activity = columnName.toLowerCase()
            .replaceAll("barthel", "")
            .replaceAll("fim", "")
            .replaceAll("index", "")
            .replaceAll("^b", "")
            .replaceAll("_", " ")
            .trim();
        
        if (activity.isEmpty()) {
            return "activity";
        }
        
        return activity;
    }
}
