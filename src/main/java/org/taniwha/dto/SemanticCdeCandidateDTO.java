package org.taniwha.dto;

import java.util.List;

public record SemanticCdeCandidateDTO(
        String semanticCdePath,
        List<String> descriptions,
        double lexicalScore,
        boolean lexicalMatch
) {
}
