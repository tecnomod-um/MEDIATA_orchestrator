package org.taniwha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.SemanticCdeCandidateDTO;
import org.taniwha.dto.SemanticCdeCandidateScoreDTO;
import org.taniwha.dto.SemanticCdeScoringRequestDTO;
import org.taniwha.util.MappingMathUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class SemanticCdeScoringService {

    static final int MAX_DATASET_FIELDS = 24;
    static final int MAX_CANDIDATES = 64;
    static final int MAX_DESCRIPTIONS = 16;
    static final int MAX_TEXT_LENGTH = 1_000;
    private final EmbeddingsClient embeddingsClient;
    private final double minimumSimilarity;
    private final double semanticWeight;

    public SemanticCdeScoringService(
            EmbeddingsClient embeddingsClient,
            @Value("${semantic.cde.embedding.minimum-similarity:0.35}") double minimumSimilarity,
            @Value("${semantic.cde.embedding.semantic-weight:0.6}") double semanticWeight
    ) {
        this.embeddingsClient = embeddingsClient;
        this.minimumSimilarity = clamp(minimumSimilarity);
        this.semanticWeight = clamp(semanticWeight);
    }

    public List<SemanticCdeCandidateScoreDTO> scoreCandidates(SemanticCdeScoringRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("A Semantic-CDE scoring request is required.");
        }

        List<String> fields = cleanTexts(request.datasetFields(), MAX_DATASET_FIELDS);
        List<SemanticCdeCandidateDTO> candidates = request.candidates() == null
                ? List.of()
                : request.candidates();
        if (candidates.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("Too many Semantic-CDE candidates.");
        }

        List<float[]> fieldVectors = embedAll(fields);
        boolean modelAvailable = fields.isEmpty() || fieldVectors.stream().allMatch(SemanticCdeScoringService::usableVector);
        List<SemanticCdeCandidateScoreDTO> scores = new ArrayList<>();

        for (SemanticCdeCandidateDTO candidate : candidates) {
            validateCandidate(candidate);
            double lexicalScore = clamp(candidate.lexicalScore());
            Double semanticSimilarity = modelAvailable
                    ? schemaSimilarity(fieldVectors, cleanTexts(candidate.descriptions(), MAX_DESCRIPTIONS))
                    : null;
            boolean accepted = semanticSimilarity == null
                    ? candidate.lexicalMatch()
                    : semanticSimilarity >= minimumSimilarity;
            double combinedScore = semanticSimilarity == null
                    ? lexicalScore
                    : (1.0 - semanticWeight) * lexicalScore + semanticWeight * semanticSimilarity;

            scores.add(new SemanticCdeCandidateScoreDTO(
                    candidate.semanticCdePath().trim(),
                    accepted,
                    semanticSimilarity,
                    combinedScore
            ));
        }

        return scores.stream()
                .sorted(Comparator.comparingDouble(SemanticCdeCandidateScoreDTO::score).reversed()
                        .thenComparing(SemanticCdeCandidateScoreDTO::semanticCdePath))
                .toList();
    }

    private Double schemaSimilarity(List<float[]> fieldVectors, List<String> descriptions) {
        if (fieldVectors.isEmpty() || descriptions.isEmpty()) {
            return null;
        }

        List<float[]> descriptionVectors = embedAll(descriptions);
        if (descriptionVectors.stream().anyMatch(vector -> !usableVector(vector))) {
            return null;
        }

        List<Double> bestFieldScores = new ArrayList<>();
        for (float[] fieldVector : fieldVectors) {
            double best = -1.0;
            for (float[] descriptionVector : descriptionVectors) {
                best = Math.max(best, MappingMathUtil.cosine(fieldVector, descriptionVector));
            }
            bestFieldScores.add(best);
        }

        return bestFieldScores.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);
    }

    private List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(embeddingsClient::embed).toList();
    }

    private static List<String> cleanTexts(List<String> values, int maximumItems) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maximumItems) {
            throw new IllegalArgumentException("Too much Semantic-CDE scoring metadata.");
        }

        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String text = value.trim();
            if (text.length() > MAX_TEXT_LENGTH) {
                throw new IllegalArgumentException("Semantic-CDE scoring text is too long.");
            }
            cleaned.add(text);
        }
        return List.copyOf(cleaned);
    }

    private static void validateCandidate(SemanticCdeCandidateDTO candidate) {
        if (candidate == null || candidate.semanticCdePath() == null
                || candidate.semanticCdePath().isBlank()) {
            throw new IllegalArgumentException("Every Semantic-CDE candidate requires a path.");
        }
        if (candidate.semanticCdePath().length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Semantic-CDE candidate path is too long.");
        }
    }

    private static boolean usableVector(float[] vector) {
        if (vector == null || vector.length == 0) {
            return false;
        }
        double norm = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                return false;
            }
            norm += (double) value * value;
        }
        return norm >= 1.0e-12;
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}
