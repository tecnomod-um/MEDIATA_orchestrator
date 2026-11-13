package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.SemanticCdeCandidateDTO;
import org.taniwha.dto.SemanticCdeScoringRequestDTO;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticCdeScoringServiceTest {

    @Test
    void acceptsAndRanksSemanticallyMatchingCandidate() {
        EmbeddingsClient embeddings = mock(EmbeddingsClient.class);
        when(embeddings.embed("systolic pressure")).thenReturn(new float[]{1, 0, 0});
        when(embeddings.embed("diastolic pressure")).thenReturn(new float[]{0, 1, 0});
        when(embeddings.embed("Clinical data element: systolic blood pressure"))
                .thenReturn(new float[]{1, 0, 0});
        when(embeddings.embed("Clinical data element: diastolic blood pressure"))
                .thenReturn(new float[]{0, 1, 0});
        when(embeddings.embed("Clinical data element: diagnosis"))
                .thenReturn(new float[]{0, 0, 1});

        SemanticCdeScoringService service = new SemanticCdeScoringService(embeddings, 0.55, 0.6);
        var scores = service.scoreCandidates(new SemanticCdeScoringRequestDTO(
                List.of("systolic pressure", "diastolic pressure"),
                List.of(
                        new SemanticCdeCandidateDTO("diagnosis.ttl",
                                List.of("Clinical data element: diagnosis"), 0.8, true),
                        new SemanticCdeCandidateDTO("blood-pressure.ttl", List.of(
                                "Clinical data element: systolic blood pressure",
                                "Clinical data element: diastolic blood pressure"
                        ), 0.5, true)
                )
        ));

        assertThat(scores).extracting("semanticCdePath")
                .containsExactly("blood-pressure.ttl", "diagnosis.ttl");
        assertThat(scores.get(0).accepted()).isTrue();
        assertThat(scores.get(0).semanticSimilarity()).isEqualTo(1.0);
        assertThat(scores.get(0).score()).isEqualTo(0.8);
        assertThat(scores.get(1).accepted()).isFalse();
        assertThat(scores.get(1).semanticSimilarity()).isEqualTo(0.0);
    }

    @Test
    void fallsBackToLexicalScoreWhenEmbeddingModelReturnsZeroVectors() {
        EmbeddingsClient embeddings = mock(EmbeddingsClient.class);
        when(embeddings.embed("blood pressure")).thenReturn(new float[768]);

        SemanticCdeScoringService service = new SemanticCdeScoringService(embeddings, 0.55, 0.6);
        var scores = service.scoreCandidates(new SemanticCdeScoringRequestDTO(
                List.of("blood pressure"),
                List.of(new SemanticCdeCandidateDTO(
                        "blood-pressure.ttl", List.of("Blood pressure"), 0.65, true
                ))
        ));

        assertThat(scores).singleElement().satisfies(score -> {
            assertThat(score.accepted()).isTrue();
            assertThat(score.semanticSimilarity()).isNull();
            assertThat(score.score()).isEqualTo(0.65);
        });
    }

    @Test
    void rejectsWeakLexicalFallbackWhenEmbeddingModelIsUnavailable() {
        EmbeddingsClient embeddings = mock(EmbeddingsClient.class);
        when(embeddings.embed("diagnosis")).thenReturn(new float[768]);

        SemanticCdeScoringService service = new SemanticCdeScoringService(embeddings, 0.55, 0.6);
        var scores = service.scoreCandidates(new SemanticCdeScoringRequestDTO(
                List.of("diagnosis"),
                List.of(new SemanticCdeCandidateDTO(
                        "blood-pressure.ttl", List.of("Blood pressure"), 0.1, false
                ))
        ));

        assertThat(scores).singleElement().satisfies(score -> {
            assertThat(score.accepted()).isFalse();
            assertThat(score.semanticSimilarity()).isNull();
            assertThat(score.score()).isEqualTo(0.1);
        });
    }

    @Test
    void rejectsUnboundedCandidateRequestsBeforeEmbedding() {
        SemanticCdeScoringService service = new SemanticCdeScoringService(mock(EmbeddingsClient.class), 0.35, 0.6);
        List<SemanticCdeCandidateDTO> candidates = Collections.nCopies(
                SemanticCdeScoringService.MAX_CANDIDATES + 1,
                new SemanticCdeCandidateDTO("candidate.ttl", List.of("description"), 0.5, true)
        );

        assertThatThrownBy(() -> service.scoreCandidates(
                new SemanticCdeScoringRequestDTO(List.of("field"), candidates)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Too many");
    }

    @Test
    void averagesSemanticEvidenceAcrossTheCompleteSchema() {
        EmbeddingsClient embeddings = mock(EmbeddingsClient.class);
        when(embeddings.embed("systolic")).thenReturn(new float[]{1, 0});
        when(embeddings.embed("diastolic")).thenReturn(new float[]{1, 0});
        when(embeddings.embed("unit")).thenReturn(new float[]{1, 0});
        when(embeddings.embed("unrelated field")).thenReturn(new float[]{0, 1});
        when(embeddings.embed("Blood pressure")).thenReturn(new float[]{1, 0});

        SemanticCdeScoringService service = new SemanticCdeScoringService(embeddings, 0.70, 0.6);
        var scores = service.scoreCandidates(new SemanticCdeScoringRequestDTO(
                List.of("systolic", "diastolic", "unit", "unrelated field"),
                List.of(new SemanticCdeCandidateDTO(
                        "blood-pressure.ttl", List.of("Blood pressure"), 1.0, true
                ))
        ));

        assertThat(scores).singleElement().satisfies(score -> {
            assertThat(score.semanticSimilarity()).isEqualTo(0.75);
            assertThat(score.accepted()).isTrue();
        });
    }
}
