package org.taniwha.controller;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.SemanticCdeCandidateDTO;
import org.taniwha.dto.SemanticCdeCandidateScoreDTO;
import org.taniwha.dto.SemanticCdeScoringRequestDTO;
import org.taniwha.service.SemanticCdeScoringService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticCdeScoringControllerTest {

    @Test
    void delegatesCandidateScoringToTheCentralEmbeddingService() {
        SemanticCdeScoringService service = mock(SemanticCdeScoringService.class);
        SemanticCdeScoringController controller = new SemanticCdeScoringController(service);
        SemanticCdeScoringRequestDTO request = new SemanticCdeScoringRequestDTO(
                List.of("systolic pressure"),
                List.of(new SemanticCdeCandidateDTO(
                        "blood-pressure.ttl", List.of("Blood pressure"), 0.5, true
                ))
        );
        List<SemanticCdeCandidateScoreDTO> expected = List.of(
                new SemanticCdeCandidateScoreDTO("blood-pressure.ttl", true, 0.9, 0.66)
        );
        when(service.scoreCandidates(request)).thenReturn(expected);

        var response = controller.scoreCandidates(request);

        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).scoreCandidates(request);
    }

    @Test
    void returnsBadRequestForInvalidScoringMetadata() {
        SemanticCdeScoringService service = mock(SemanticCdeScoringService.class);
        SemanticCdeScoringController controller = new SemanticCdeScoringController(service);
        SemanticCdeScoringRequestDTO request = new SemanticCdeScoringRequestDTO(List.of(), List.of());
        when(service.scoreCandidates(request)).thenThrow(new IllegalArgumentException("Too many candidates."));

        var response = controller.invalidRequest(new IllegalArgumentException("Too many candidates."));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("Too many candidates.");
    }
}
