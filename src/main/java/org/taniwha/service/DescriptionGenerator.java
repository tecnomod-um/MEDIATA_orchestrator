package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DescriptionGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DescriptionGenerator.class);

    // Domain-specific description templates
    private static final Map<String, String> ASSESSMENT_DESCRIPTIONS = new HashMap<>();
    
    static {
        // Barthel Index descriptions
        ASSESSMENT_DESCRIPTIONS.put("barthel", "Assessment of activities of daily living measuring degree of independence");
        ASSESSMENT_DESCRIPTIONS.put("feed", "Ability to feed oneself independently");
        ASSESSMENT_DESCRIPTIONS.put("eating", "Level of assistance required for eating");
        ASSESSMENT_DESCRIPTIONS.put("groom", "Personal grooming and hygiene independence");
        ASSESSMENT_DESCRIPTIONS.put("grooming", "Self-care ability for personal hygiene");
        ASSESSMENT_DESCRIPTIONS.put("dress", "Ability to dress and undress independently");
        ASSESSMENT_DESCRIPTIONS.put("dressing", "Level of independence in dressing");
        ASSESSMENT_DESCRIPTIONS.put("bath", "Bathing and showering independence");
        ASSESSMENT_DESCRIPTIONS.put("bathing", "Level of assistance needed for bathing");
        ASSESSMENT_DESCRIPTIONS.put("toilet", "Toileting independence and bladder/bowel control");
        ASSESSMENT_DESCRIPTIONS.put("bowel", "Bowel control and management independence");
        ASSESSMENT_DESCRIPTIONS.put("bladder", "Bladder control and continence");
        ASSESSMENT_DESCRIPTIONS.put("transfer", "Ability to transfer between bed, chair, and wheelchair");
        ASSESSMENT_DESCRIPTIONS.put("mobility", "Walking and movement independence");
        ASSESSMENT_DESCRIPTIONS.put("stair", "Ability to climb stairs independently");
        ASSESSMENT_DESCRIPTIONS.put("stairs", "Level of assistance needed for stair climbing");
        
        // FIM descriptions
        ASSESSMENT_DESCRIPTIONS.put("fim", "Functional Independence Measure assessing physical and cognitive disability");
        ASSESSMENT_DESCRIPTIONS.put("locomotion", "Ability to move and ambulate");
        ASSESSMENT_DESCRIPTIONS.put("memory", "Cognitive ability to remember and recall");
        ASSESSMENT_DESCRIPTIONS.put("comprehension", "Ability to understand spoken language");
        ASSESSMENT_DESCRIPTIONS.put("expression", "Ability to express thoughts verbally");
        ASSESSMENT_DESCRIPTIONS.put("social", "Social interaction skills");
        ASSESSMENT_DESCRIPTIONS.put("problem", "Problem-solving and decision-making ability");
        
        // Medical conditions
        ASSESSMENT_DESCRIPTIONS.put("stroke", "Cerebrovascular accident affecting brain function");
        ASSESSMENT_DESCRIPTIONS.put("type", "Classification or category of condition");
        ASSESSMENT_DESCRIPTIONS.put("etiology", "Cause or origin of the condition");
        ASSESSMENT_DESCRIPTIONS.put("ischemic", "Condition caused by reduced blood flow");
        ASSESSMENT_DESCRIPTIONS.put("hemorrhagic", "Condition involving bleeding");
        
        // Demographics and general
        ASSESSMENT_DESCRIPTIONS.put("age", "Patient age at time of assessment");
        ASSESSMENT_DESCRIPTIONS.put("sex", "Biological sex or gender of patient");
        ASSESSMENT_DESCRIPTIONS.put("gender", "Gender identity of patient");
        ASSESSMENT_DESCRIPTIONS.put("dominance", "Dominant hand preference (left/right)");
        ASSESSMENT_DESCRIPTIONS.put("education", "Level of educational attainment");
    }

    /**
     * Generate a human-readable description for a column based on its name and terminology
     */
    public String generateColumnDescription(String columnName, String terminology) {
        if (columnName == null || columnName.trim().isEmpty()) {
            return "";
        }

        String lowerName = columnName.toLowerCase();
        
        // Check if we have a template description
        for (Map.Entry<String, String> entry : ASSESSMENT_DESCRIPTIONS.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // If we have terminology, use it to generate description
        if (terminology != null && !terminology.trim().isEmpty()) {
            return "Clinical measure related to " + terminology.toLowerCase();
        }

        // Generic description based on column name
        return "Clinical data element: " + cleanColumnName(columnName);
    }

    /**
     * Generate a human-readable description for a value based on context
     */
    public String generateValueDescription(String valueName, String columnContext, List<String> allValues) {
        if (valueName == null || valueName.trim().isEmpty()) {
            return "";
        }

        String lowerValue = valueName.toLowerCase();
        String lowerContext = columnContext != null ? columnContext.toLowerCase() : "";

        // Numeric range values
        if (valueName.matches("\\d+-\\d+") || valueName.matches("integer|double")) {
            if (lowerContext.contains("barthel") || lowerContext.contains("fim")) {
                return describeAssessmentScore(valueName, lowerContext);
            }
            return "Numeric measurement range";
        }

        // Independence levels (common in Barthel/FIM)
        if (lowerValue.contains("independent")) {
            return "Complete independence, no assistance required";
        }
        if (lowerValue.contains("dependent") || lowerValue.matches("\\d+") && lowerContext.contains("barthel")) {
            return inferDependenceLevel(valueName, columnContext, allValues);
        }
        if (lowerValue.contains("assist") || lowerValue.contains("help")) {
            return "Partial assistance or help needed";
        }

        // Medical conditions
        if (lowerValue.contains("ischemic") || lowerValue.equals("isch")) {
            return "Ischemic type - caused by reduced blood flow";
        }
        if (lowerValue.contains("hemorrhagic") || lowerValue.equals("hem")) {
            return "Hemorrhagic type - caused by bleeding";
        }

        // Yes/No values
        if (lowerValue.matches("[yn]|yes|no")) {
            return "Binary indicator (yes/no)";
        }

        // Gender/Sex
        if (lowerContext.contains("sex") || lowerContext.contains("gender")) {
            if (lowerValue.matches("[mfhd]|male|female")) {
                return "Biological sex or gender category";
            }
        }

        // Generic categorical value
        if (allValues != null && allValues.size() > 1 && allValues.size() <= 10) {
            return "One of " + allValues.size() + " categorical values";
        }

        return "Clinical observation value";
    }

    private String describeAssessmentScore(String valueName, String context) {
        if (context.contains("barthel")) {
            // Barthel scores typically 0-100 or subscales 0-15
            if (valueName.contains("0-") || valueName.startsWith("0")) {
                return "Score range indicating dependence to independence level";
            }
            return "Barthel index score - higher values indicate greater independence";
        }
        if (context.contains("fim")) {
            // FIM scores typically 1-7 per item
            return "FIM score - higher values indicate greater functional independence";
        }
        return "Assessment score or measurement value";
    }

    private String inferDependenceLevel(String value, String context, List<String> allValues) {
        if (context == null || allValues == null || allValues.isEmpty()) {
            return "Functional status level";
        }

        // Try to infer if this is low/medium/high based on numeric value
        try {
            int val = Integer.parseInt(value.trim());
            int max = findMaxValue(allValues);
            
            if (max > 0) {
                double ratio = (double) val / max;
                if (ratio < 0.33) {
                    return "Low functional level - significant assistance required";
                } else if (ratio < 0.67) {
                    return "Moderate functional level - some assistance needed";
                } else {
                    return "High functional level - minimal or no assistance needed";
                }
            }
        } catch (NumberFormatException e) {
            // Not a numeric value
        }

        return "Functional independence level";
    }

    private int findMaxValue(List<String> values) {
        int max = 0;
        for (String v : values) {
            try {
                int val = Integer.parseInt(v.trim());
                max = Math.max(max, val);
            } catch (NumberFormatException e) {
                // Skip non-numeric values
            }
        }
        return max;
    }

    private String cleanColumnName(String name) {
        return name.replaceAll("_", " ")
                   .replaceAll("([a-z])([A-Z])", "$1 $2")
                   .toLowerCase();
    }
}
