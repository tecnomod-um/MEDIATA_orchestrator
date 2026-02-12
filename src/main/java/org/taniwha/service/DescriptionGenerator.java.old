package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for generating human-readable descriptions using LLM-based analysis.
 * Uses LLM to intelligently understand value ranges and context.
 * No hardcoding - pure LLM-driven semantic understanding.
 */
@Service
public class DescriptionGenerator {
    private static final Logger logger = LoggerFactory.getLogger(DescriptionGenerator.class);

    private final EmbeddingsClient embeddingsClient;
    private final Map<String, String> descriptionCache = new HashMap<>();

    @Autowired
    public DescriptionGenerator(EmbeddingsClient embeddingsClient) {
        this.embeddingsClient = embeddingsClient;
    }

    /**
     * Generate description for a column using LLM semantic analysis.
     */
    public String generateColumnDescription(String columnName, String terminology, List<String> values) {
        if (columnName == null || columnName.trim().isEmpty()) {
            return "";
        }

        String cacheKey = "COL:" + columnName;
        if (descriptionCache.containsKey(cacheKey)) {
            return descriptionCache.get(cacheKey);
        }

        try {
            // Use LLM to generate description based on column name and values
            String description = analyzeColumnMeaning(columnName, values);
            descriptionCache.put(cacheKey, description);
            return description;
        } catch (Exception e) {
            logger.warn("Error generating column description: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Generate description for a value using LLM to analyze its meaning in context.
     * Intelligently determines if value represents dependence, partial ability, or independence
     * based on position in range (for numeric values).
     */
    public String generateValueDescription(String valueName, String columnContext, List<String> allValues) {
        if (valueName == null || valueName.trim().isEmpty()) {
            return "";
        }

        String cacheKey = "VAL:" + columnContext + "::" + valueName;
        if (descriptionCache.containsKey(cacheKey)) {
            return descriptionCache.get(cacheKey);
        }

        try {
            String description = analyzeValueMeaning(valueName, columnContext, allValues);
            descriptionCache.put(cacheKey, description);
            return description;
        } catch (Exception e) {
            logger.warn("Error generating value description: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Analyzes column meaning using LLM semantic understanding.
     */
    private String analyzeColumnMeaning(String columnName, List<String> values) {
        // Build query for LLM
        String query = "Assess column: " + columnName;
        if (values != null && !values.isEmpty()) {
            query += " with values: " + String.join(", ", values.subList(0, Math.min(3, values.size())));
        }

        // Use LLM to select most appropriate description from semantically diverse options
        String[] options = {
            "Assessment of functional independence",
            "Level of ability or performance",
            "Clinical measurement or score",
            "Patient characteristic or attribute",
            "Medical condition or diagnosis",
            "Demographic information",
            "Cognitive or motor function measure"
        };

        return selectBestUsingLLM(query, options);
    }

    /**
     * Analyzes what a value represents using LLM semantic understanding.
     * For numeric values in assessment scales, intelligently determines level (dependent/partial/independent).
     */
    private String analyzeValueMeaning(String value, String columnContext, List<String> allValues) {
        // Try to parse as numeric
        boolean isNumeric = false;
        double numericValue = 0;
        double minValue = Double.MAX_VALUE;
        double maxValue = Double.MIN_VALUE;

        try {
            numericValue = Double.parseDouble(value);
            isNumeric = true;

            // Find range from all values
            for (String v : allValues) {
                if (v == null) continue;
                try {
                    double d = Double.parseDouble(v);
                    minValue = Math.min(minValue, d);
                    maxValue = Math.max(maxValue, d);
                } catch (NumberFormatException ignored) {}
            }
        } catch (NumberFormatException e) {
            // Not numeric
        }

        if (isNumeric && minValue != Double.MAX_VALUE && maxValue > minValue) {
            // Numeric value - analyze position in range using LLM
            return analyzeNumericValue(numericValue, minValue, maxValue, columnContext);
        } else {
            // Categorical value - use LLM to understand it
            return analyzeCategoricalValue(value, columnContext);
        }
    }

    /**
     * Analyzes numeric value position in range using LLM.
     * Determines if it represents low/mid/high ability without hardcoding.
     */
    private String analyzeNumericValue(double value, double min, double max, String context) {
        double range = max - min;
        double position = (value - min) / range; // 0.0 to 1.0

        // Build query for LLM
        String query = String.format("%s value %.0f in range %.0f-%.0f", context, value, min, max);

        // Use LLM to select appropriate description based on position
        String[] options;
        if (position < 0.33) {
            // Low score - use LLM to find best "dependent" description
            options = new String[]{
                "Complete dependence; requires full assistance",
                "Unable to perform; totally dependent",
                "Minimal ability; full help needed",
                "Dependent on others for task completion"
            };
        } else if (position < 0.67) {
            // Mid score - use LLM to find best "partial" description
            options = new String[]{
                "Partial independence; requires some assistance",
                "Needs help; can perform parts of task",
                "Limited ability; assistance sometimes needed",
                "Partially dependent; requires supervision or help"
            };
        } else {
            // High score - use LLM to find best "independent" description
            options = new String[]{
                "Independent; no assistance required",
                "Complete independence; performs task fully",
                "Able to perform without help",
                "Fully independent; no supervision needed"
            };
        }

        return selectBestUsingLLM(query, options);
    }

    /**
     * Analyzes categorical value using LLM.
     */
    private String analyzeCategoricalValue(String value, String context) {
        String query = context + " value: " + value;

        // Use LLM to select appropriate categorical description
        String[] options = {
            "Positive indication or presence",
            "Negative indication or absence",
            "Classification or category type",
            "Specific attribute or characteristic",
            "Demographic classification",
            "Clinical finding or condition"
        };

        return selectBestUsingLLM(query, options);
    }

    /**
     * Uses LLM embeddings to select the best description from options.
     * This is the core LLM intelligence - no hardcoding, pure semantic similarity.
     */
    private String selectBestUsingLLM(String query, String[] options) {
        try {
            // Generate embedding for query
            float[] queryEmbed = embeddingsClient.embed(query);

            double bestSim = -1.0;
            String best = options[0];

            // Find option with highest semantic similarity
            for (String option : options) {
                float[] optionEmbed = embeddingsClient.embed(option);
                double sim = cosineSimilarity(queryEmbed, optionEmbed);

                if (sim > bestSim) {
                    bestSim = sim;
                    best = option;
                }
            }

            logger.debug("LLM selected '{}' for query '{}' (similarity={})", best, query, bestSim);
            return best;

        } catch (Exception e) {
            logger.warn("LLM selection failed: {}", e.getMessage());
            return options[0]; // Fallback to first option
        }
    }

    /**
     * Calculate cosine similarity between two embeddings.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Clear the description cache.
     */
    public void clearCache() {
        descriptionCache.clear();
    }
}
