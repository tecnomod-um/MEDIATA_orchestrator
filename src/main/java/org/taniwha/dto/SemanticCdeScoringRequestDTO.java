package org.taniwha.dto;

import java.util.List;

public record SemanticCdeScoringRequestDTO(
        List<String> datasetFields,
        List<SemanticCdeCandidateDTO> candidates
) {
}
