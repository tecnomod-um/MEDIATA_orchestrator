package org.taniwha.dto;

public record SemanticCdeCandidateScoreDTO(
        String semanticCdePath,
        boolean accepted,
        Double semanticSimilarity,
        double score
) {
}
