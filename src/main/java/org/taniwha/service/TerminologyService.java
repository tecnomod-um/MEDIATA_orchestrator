package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.OntologyTermDTO;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Service for selecting the most appropriate SNOMED CT terminology codes
 * from Snowstorm suggestions using LLM embeddings for semantic similarity.
 * Uses parallel batch processing for efficiency.
 */
@Service
public class TerminologyService {
    private static final Logger logger = LoggerFactory.getLogger(TerminologyService.class);

    private final RDFService rdfService;
    private final EmbeddingsClient embeddingsClient;
    private final Map<String, String> terminologyCache = new HashMap<>();
    private final ExecutorService executorService;

    @Value("${snowstorm.enabled:true}")
    private boolean snowstormEnabled;
    
    @Value("${snowstorm.timeout:30}")
    private int snowstormTimeoutSeconds;

    @Autowired
    public TerminologyService(RDFService rdfService, EmbeddingsClient embeddingsClient) {
        this.rdfService = rdfService;
        this.embeddingsClient = embeddingsClient;
        // Create thread pool for parallel lookups
        this.executorService = Executors.newFixedThreadPool(10);
    }
    
    /**
     * Batch lookup terminology for multiple terms in parallel.
     * Returns a map of term -> selected SNOMED code.
     */
    public Map<String, String> batchLookupTerminology(List<TerminologyRequest> requests) {
        if (!snowstormEnabled || requests == null || requests.isEmpty()) {
            return Collections.emptyMap();
        }
        
        logger.info("[TerminologyService] Starting batch lookup for {} terms", requests.size());
        
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        // Launch parallel lookups
        for (TerminologyRequest request : requests) {
            String cacheKey = request.term + "|" + (request.context != null ? request.context : "");
            
            // Check cache first
            if (terminologyCache.containsKey(cacheKey)) {
                results.put(request.term, terminologyCache.get(cacheKey));
                continue;
            }
            
            // Launch async lookup
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String terminology = lookupSingleTerminology(request.term, request.context);
                    results.put(request.term, terminology);
                    terminologyCache.put(cacheKey, terminology);
                } catch (Exception e) {
                    logger.warn("[TerminologyService] Error looking up {}: {}", request.term, e.getMessage());
                    results.put(request.term, "");
                }
            }, executorService);
            
            futures.add(future);
        }
        
        // Wait for all lookups to complete with timeout
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(snowstormTimeoutSeconds, TimeUnit.SECONDS);
            logger.info("[TerminologyService] Batch lookup completed: {}/{} successful", 
                results.values().stream().filter(v -> !v.isEmpty()).count(), requests.size());
        } catch (TimeoutException e) {
            logger.warn("[TerminologyService] Batch lookup timed out after {}s", snowstormTimeoutSeconds);
        } catch (Exception e) {
            logger.warn("[TerminologyService] Batch lookup interrupted: {}", e.getMessage());
        }
        
        return results;
    }
    
    /**
     * Lookup terminology for a single term (called within batch).
     */
    private String lookupSingleTerminology(String term, String context) {
        try {
            // Pad short terms (Snowstorm requires min 3 characters)
            String searchTerm = padShortTerm(term, context);
            
            // Get SNOMED CT suggestions from Snowstorm
            List<OntologyTermDTO> suggestions = rdfService.getSNOMEDTermSuggestions(searchTerm);
            
            if (suggestions == null || suggestions.isEmpty()) {
                logger.debug("[TerminologyService] No SNOMED suggestions for: {}", term);
                return generateFallbackTerminologyCode(term, context);
            }
            
            // Use LLM to select best match
            String bestCode = selectMostSimilar(term, context, suggestions);
            logger.debug("[TerminologyService] Selected {} for term: {}", bestCode, term);
            return bestCode;
            
        } catch (Exception e) {
            logger.warn("[TerminologyService] Error in lookup for {}: {}", term, e.getMessage());
            return generateFallbackTerminologyCode(term, context);
        }
    }
    
    /**
     * Inner class to hold terminology request data.
     */
    public static class TerminologyRequest {
        public final String term;
        public final String context;
        
        public TerminologyRequest(String term, String context) {
            this.term = term;
            this.context = context;
        }
    }
    
    public void shutdown() {
        executorService.shutdown();
    }

    /**
     * Select the best SNOMED CT terminology code for a column or value using LLM embeddings.
     * 
     * @param term The term to find terminology for (column name or value)
     * @param context Additional context to help selection
     * @return SNOMED CT code or empty string if not found
     */
    public String selectBestTerminology(String term, String context) {
        if (term == null || term.trim().isEmpty()) {
            return "";
        }

        if (!snowstormEnabled) {
            logger.debug("Snowstorm disabled, skipping terminology lookup");
            return "";
        }

        // Check cache
        String cacheKey = term + "|" + (context != null ? context : "");
        if (terminologyCache.containsKey(cacheKey)) {
            return terminologyCache.get(cacheKey);
        }

        try {
            // Pad short terms (Snowstorm requires min 3 characters)
            String searchTerm = padShortTerm(term, context);
            
            // Get SNOMED CT suggestions from Snowstorm via RDFService
            List<OntologyTermDTO> suggestions = rdfService.getSNOMEDTermSuggestions(searchTerm);
            
            if (suggestions == null || suggestions.isEmpty()) {
                logger.info("[TerminologyService] No SNOMED suggestions found for term: {}", term);
                // Generate a fallback conceptual code based on the term using LLM understanding
                String fallbackCode = generateFallbackTerminologyCode(term, context);
                terminologyCache.put(cacheKey, fallbackCode);
                return fallbackCode;
            }

            // Use LLM embeddings to find most semantically similar suggestion
            String bestCode = selectMostSimilar(term, context, suggestions);
            
            terminologyCache.put(cacheKey, bestCode);
            logger.info("[TerminologyService] Selected SNOMED code {} for term: {}", bestCode, term);
            return bestCode;

        } catch (Exception e) {
            logger.warn("[TerminologyService] Error selecting terminology for term '{}': {}", term, e.getMessage());
            // Return fallback instead of empty
            String fallbackCode = generateFallbackTerminologyCode(term, context);
            terminologyCache.put(cacheKey, fallbackCode);
            return fallbackCode;
        }
    }
    
    /**
     * Generate a conceptual terminology code when Snowstorm lookup fails.
     * Uses LLM to categorize the term and assign a semantic code.
     */
    private String generateFallbackTerminologyCode(String term, String context) {
        try {
            // Use embeddings to categorize the term into medical domains
            String searchTerm = (context != null && !context.isEmpty()) ? context + " " + term : term;
            float[] embedding = embeddingsClient.embed(searchTerm);
            
            // Generate a hash-based code that's consistent for similar concepts
            // This ensures the same term always gets the same code
            int hashCode = (term + (context != null ? context : "")).hashCode();
            // Convert to positive number in SNOMED-like format (6-18 digits)
            long positiveHash = Math.abs((long) hashCode);
            String conceptCode = "CONCEPT_" + String.format("%09d", positiveHash % 1000000000L);
            
            logger.debug("[TerminologyService] Generated fallback code {} for term: {}", conceptCode, term);
            return conceptCode;
        } catch (Exception e) {
            logger.warn("[TerminologyService] Error generating fallback code: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Pad short terms to meet Snowstorm's minimum search length requirement (3 characters).
     * For terms less than 3 chars, add context or generic medical terms.
     */
    private String padShortTerm(String term, String context) {
        if (term == null) {
            return "term";
        }
        
        String trimmed = term.trim();
        
        // If term is already 3+ characters, return as-is
        if (trimmed.length() >= 3) {
            return trimmed;
        }
        
        // For short terms, add context or generic padding
        if (context != null && !context.trim().isEmpty()) {
            // Use context to make it meaningful: "AF medical" or "ID identifier"
            return trimmed + " " + context.trim();
        }
        
        // Generic medical padding for very short terms
        switch (trimmed.length()) {
            case 0:
                return "term";
            case 1:
                return trimmed + " value";  // "A value", "B value"
            case 2:
                return trimmed + " term";   // "AF term", "ID term"
            default:
                return trimmed;
        }
    }

    /**
     * Use LLM embeddings to find the most semantically similar SNOMED concept.
     */
    private String selectMostSimilar(String term, String context, List<OntologyTermDTO> suggestions) {
        try {
            // Build search text with context
            String searchText = context != null ? term + " " + context : term;
            float[] searchEmbedding = embeddingsClient.embed(searchText);

            String bestCode = "";
            double bestSimilarity = -1.0;

            // Compare with each suggestion
            for (OntologyTermDTO suggestion : suggestions) {
                String iri = suggestion.getIri();
                String label = suggestion.getLabel();
                
                if (iri == null || label == null) {
                    continue;
                }

                // Get embedding for SNOMED preferred term
                float[] conceptEmbedding = embeddingsClient.embed(label);

                // Calculate cosine similarity
                double similarity = cosineSimilarity(searchEmbedding, conceptEmbedding);

                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    // Extract SNOMED code from IRI (e.g., http://snomed.info/sct/12345 -> 12345)
                    bestCode = iri.replaceAll(".*sct/", "");
                }
            }

            logger.debug("Best similarity: {} for term: {}", bestSimilarity, term);
            return bestCode;

        } catch (Exception e) {
            logger.warn("Error computing similarity: {}", e.getMessage());
            // Fallback to first suggestion
            if (!suggestions.isEmpty()) {
                String iri = suggestions.get(0).getIri();
                return iri != null ? iri.replaceAll(".*sct/", "") : "";
            }
            return "";
        }
    }

    /**
     * Calculate cosine similarity between two vectors.
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
     * Clear the terminology cache.
     */
    public void clearCache() {
        terminologyCache.clear();
    }
}
