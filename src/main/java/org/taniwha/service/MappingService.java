package org.taniwha.service;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MappingService {

    private static final Logger logger = LoggerFactory.getLogger(MappingService.class);
    private static final double QUALITY_THRESHOLD = 0.7;

    /**
     * Suggests mappings using both math-based embeddings and LLM,
     * with quality control to ensure LLM output is not worse than math baseline.
     */
    public List<MappingSuggestion> suggestMappings(String sourceField, List<String> targetFields) {
        logger.debug("Generating mapping suggestions for '{}' against {} target fields", sourceField, targetFields.size());

        // Get baseline suggestions using math-based embeddings
        List<MappingSuggestion> mathBasedSuggestions = getMathBasedSuggestions(sourceField, targetFields);
        
        // Get LLM-enhanced suggestions
        List<MappingSuggestion> llmSuggestions = getLLMEnhancedSuggestions(sourceField, targetFields, mathBasedSuggestions);
        
        // Quality control: ensure LLM output is not worse than math baseline
        List<MappingSuggestion> finalSuggestions = applyQualityControl(mathBasedSuggestions, llmSuggestions);
        
        logger.info("Generated {} final suggestions for '{}'", finalSuggestions.size(), sourceField);
        return finalSuggestions;
    }

    /**
     * Math-based embeddings using TF-IDF and cosine similarity
     */
    private List<MappingSuggestion> getMathBasedSuggestions(String source, List<String> targets) {
        List<MappingSuggestion> suggestions = new ArrayList<>();
        
        for (String target : targets) {
            double score = calculateCosineSimilarity(source, target);
            suggestions.add(new MappingSuggestion(target, score, "math-based"));
        }
        
        // Sort by score descending
        suggestions.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        return suggestions.stream()
                .filter(s -> s.getScore() >= QUALITY_THRESHOLD)
                .collect(Collectors.toList());
    }

    /**
     * LLM-enhanced suggestions with contextual understanding
     */
    private List<MappingSuggestion> getLLMEnhancedSuggestions(String source, List<String> targets, 
                                                               List<MappingSuggestion> mathBaseline) {
        List<MappingSuggestion> llmSuggestions = new ArrayList<>();
        
        // For now, enhance math-based scores with semantic understanding
        // This is where actual LLM API calls would go
        for (String target : targets) {
            double mathScore = mathBaseline.stream()
                    .filter(s -> s.getTargetField().equals(target))
                    .map(MappingSuggestion::getScore)
                    .findFirst()
                    .orElse(0.0);
            
            // Simulate LLM enhancement - in real implementation, call LLM API here
            double llmScore = enhanceWithLLM(source, target, mathScore);
            llmSuggestions.add(new MappingSuggestion(target, llmScore, "llm-enhanced"));
        }
        
        // Sort by score descending
        llmSuggestions.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        return llmSuggestions;
    }

    /**
     * Simulate LLM enhancement - placeholder for actual LLM API integration
     */
    private double enhanceWithLLM(String source, String target, double baseScore) {
        // For now, use semantic rules to enhance the baseline
        // In production, this would call an LLM API
        
        double enhancement = 0.0;
        
        // Special handling for known abbreviations
        if (isKnownAbbreviation(source, target)) {
            enhancement += 0.5; // Strong boost for known abbreviations
        }
        
        // Semantic similarity checks
        if (areSemanticallyRelated(source, target)) {
            enhancement += 0.15;
        }
        
        // Abbreviation matching
        if (isAbbreviationMatch(source, target)) {
            enhancement += 0.1;
        }
        
        // Medical terminology matching
        if (isMedicalTermMatch(source, target)) {
            enhancement += 0.1;
        }
        
        return Math.min(1.0, baseScore + enhancement);
    }
    
    /**
     * Check for known abbreviations (LLM would have this knowledge)
     */
    private boolean isKnownAbbreviation(String abbr, String fullForm) {
        Map<String, List<String>> knownAbbreviations = new HashMap<>();
        knownAbbreviations.put("dob", Arrays.asList("date_of_birth", "dateofbirth", "birthdate", "birth_date", "patient_dob"));
        knownAbbreviations.put("mrn", Arrays.asList("medical_record_number", "medicalrecordnumber", "patient_mrn"));
        knownAbbreviations.put("ssn", Arrays.asList("social_security_number", "socialsecuritynumber"));
        knownAbbreviations.put("id", Arrays.asList("identifier", "patient_id", "patientid"));
        
        String abbrLower = abbr.toLowerCase();
        String fullLower = fullForm.toLowerCase();
        
        // Check if abbr maps to fullForm
        List<String> expansions = knownAbbreviations.get(abbrLower);
        if (expansions != null) {
            for (String expansion : expansions) {
                if (fullLower.contains(expansion) || expansion.contains(fullLower)) {
                    return true;
                }
            }
        }
        
        // Check reverse - if fullForm contains the abbreviation
        for (Map.Entry<String, List<String>> entry : knownAbbreviations.entrySet()) {
            if (fullLower.contains(entry.getKey())) {
                for (String expansion : entry.getValue()) {
                    if (expansion.contains(abbrLower) || abbrLower.contains(expansion)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * Quality control: use LLM output only if it's better than or comparable to math baseline
     */
    private List<MappingSuggestion> applyQualityControl(List<MappingSuggestion> mathSuggestions,
                                                        List<MappingSuggestion> llmSuggestions) {
        List<MappingSuggestion> result = new ArrayList<>();
        
        // Calculate average scores
        double mathAvgScore = mathSuggestions.stream()
                .mapToDouble(MappingSuggestion::getScore)
                .average()
                .orElse(0.0);
        
        double llmAvgScore = llmSuggestions.stream()
                .mapToDouble(MappingSuggestion::getScore)
                .average()
                .orElse(0.0);
        
        logger.debug("Quality control - Math avg: {}, LLM avg: {}", mathAvgScore, llmAvgScore);
        
        // Determine threshold based on which method is being used
        double effectiveThreshold = QUALITY_THRESHOLD;
        
        // Use LLM suggestions if they're better or comparable (within 5% tolerance)
        if (llmAvgScore >= mathAvgScore * 0.95) {
            logger.info("Using LLM suggestions (avg score: {} vs math baseline: {})", llmAvgScore, mathAvgScore);
            result = llmSuggestions;
            // For LLM, we can be more lenient with threshold if it found semantic matches that math missed
            if (mathAvgScore == 0.0 && llmAvgScore > 0.0) {
                effectiveThreshold = 0.5; // Lower threshold when LLM finds matches that math missed
            }
        } else {
            logger.info("Using math-based suggestions (LLM avg {} is worse than math baseline {})", llmAvgScore, mathAvgScore);
            result = mathSuggestions;
        }
        
        final double threshold = effectiveThreshold;
        return result.stream()
                .filter(s -> s.getScore() >= threshold)
                .collect(Collectors.toList());
    }

    /**
     * Calculate cosine similarity using character-level TF-IDF vectors
     */
    private double calculateCosineSimilarity(String str1, String str2) {
        str1 = str1.toLowerCase().trim();
        str2 = str2.toLowerCase().trim();
        
        // Simple exact match
        if (str1.equals(str2)) {
            return 1.0;
        }
        
        // Build character frequency vectors
        Map<Character, Integer> freq1 = buildCharFrequency(str1);
        Map<Character, Integer> freq2 = buildCharFrequency(str2);
        
        // Get all unique characters
        Set<Character> allChars = new HashSet<>();
        allChars.addAll(freq1.keySet());
        allChars.addAll(freq2.keySet());
        
        if (allChars.isEmpty()) {
            return 0.0;
        }
        
        // Build vectors
        List<Character> charList = new ArrayList<>(allChars);
        double[] vec1 = new double[charList.size()];
        double[] vec2 = new double[charList.size()];
        
        for (int i = 0; i < charList.size(); i++) {
            char c = charList.get(i);
            vec1[i] = freq1.getOrDefault(c, 0);
            vec2[i] = freq2.getOrDefault(c, 0);
        }
        
        RealVector v1 = new ArrayRealVector(vec1);
        RealVector v2 = new ArrayRealVector(vec2);
        
        double dotProduct = v1.dotProduct(v2);
        double norm1 = v1.getNorm();
        double norm2 = v2.getNorm();
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        
        double cosineSim = dotProduct / (norm1 * norm2);
        
        // Additional string similarity boost
        double editDistSim = 1.0 - (double) levenshteinDistance(str1, str2) / Math.max(str1.length(), str2.length());
        
        // Combine cosine and edit distance with weights
        return 0.6 * cosineSim + 0.4 * editDistSim;
    }

    private Map<Character, Integer> buildCharFrequency(String str) {
        Map<Character, Integer> freq = new HashMap<>();
        for (char c : str.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_') {
                freq.put(c, freq.getOrDefault(c, 0) + 1);
            }
        }
        return freq;
    }

    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }
        
        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        
        return dp[s1.length()][s2.length()];
    }

    private boolean areSemanticallyRelated(String s1, String s2) {
        // Common medical/data field patterns
        Map<String, List<String>> semanticGroups = new HashMap<>();
        semanticGroups.put("name", Arrays.asList("name", "fullname", "full_name", "patient_name", "patientname"));
        semanticGroups.put("id", Arrays.asList("id", "identifier", "patient_id", "patientid", "mrn", "medical_record_number"));
        semanticGroups.put("date", Arrays.asList("date", "datetime", "timestamp", "time", "created_date", "birthdate", "dob"));
        semanticGroups.put("address", Arrays.asList("address", "street", "city", "postal", "zip", "location"));
        
        final String str1Lower = s1.toLowerCase();
        final String str2Lower = s2.toLowerCase();
        
        for (List<String> group : semanticGroups.values()) {
            boolean s1InGroup = group.stream().anyMatch(term -> str1Lower.contains(term) || term.contains(str1Lower));
            boolean s2InGroup = group.stream().anyMatch(term -> str2Lower.contains(term) || term.contains(str2Lower));
            if (s1InGroup && s2InGroup) {
                return true;
            }
        }
        
        return false;
    }

    private boolean isAbbreviationMatch(String s1, String s2) {
        String abbr1 = extractAbbreviation(s1);
        String abbr2 = extractAbbreviation(s2);
        
        if (abbr1.isEmpty() || abbr2.isEmpty()) {
            return false;
        }
        
        return abbr1.equalsIgnoreCase(abbr2) || 
               s1.toLowerCase().contains(abbr2.toLowerCase()) ||
               s2.toLowerCase().contains(abbr1.toLowerCase());
    }

    private String extractAbbreviation(String str) {
        // Extract capital letters or underscored parts
        StringBuilder abbr = new StringBuilder();
        for (char c : str.toCharArray()) {
            if (Character.isUpperCase(c)) {
                abbr.append(c);
            }
        }
        return abbr.toString();
    }

    private boolean isMedicalTermMatch(String s1, String s2) {
        List<String> medicalTerms = Arrays.asList(
            "patient", "diagnosis", "procedure", "medication", "allergy", "vital", "lab", "observation",
            "encounter", "condition", "immunization", "prescription", "symptom", "treatment"
        );
        
        s1 = s1.toLowerCase();
        s2 = s2.toLowerCase();
        
        for (String term : medicalTerms) {
            if ((s1.contains(term) || s2.contains(term)) && 
                (s1.contains(term) && s2.contains(term))) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * Inner class representing a mapping suggestion
     */
    public static class MappingSuggestion {
        private final String targetField;
        private final double score;
        private final String method;

        public MappingSuggestion(String targetField, double score, String method) {
            this.targetField = targetField;
            this.score = score;
            this.method = method;
        }

        public String getTargetField() {
            return targetField;
        }

        public double getScore() {
            return score;
        }

        public String getMethod() {
            return method;
        }

        @Override
        public String toString() {
            return String.format("%s (%.3f) [%s]", targetField, score, method);
        }
    }
}
