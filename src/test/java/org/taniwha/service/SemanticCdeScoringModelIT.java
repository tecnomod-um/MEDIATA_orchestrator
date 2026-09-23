package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.taniwha.dto.SemanticCdeCandidateDTO;
import org.taniwha.dto.SemanticCdeScoringRequestDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_SEMANTIC_CDE_MODEL_IT", matches = "true")
class SemanticCdeScoringModelIT {

    private static final String MODEL_URI =
            "https://huggingface.co/Dev-SN/pubmedbert-base-embeddings-onnx/resolve/main/onnx/model.onnx";
    private static final String TOKENIZER_URI =
            "https://huggingface.co/Dev-SN/pubmedbert-base-embeddings-onnx/resolve/main/tokenizer.json";

    @Test
    void productionEmbeddingModelRanksMatchingClinicalMetadataFirst() throws Exception {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setModelResource(MODEL_URI);
        model.setTokenizerResource(TOKENIZER_URI);
        model.setResourceCacheDirectory(System.getProperty("java.io.tmpdir") + "/spring-ai-onnx-model");
        model.afterPropertiesSet();

        SemanticCdeScoringService service = new SemanticCdeScoringService(
                new EmbeddingsClient(model),
                0.35,
                0.6
        );
        var scores = service.scoreCandidates(new SemanticCdeScoringRequestDTO(
                List.of("systolic blood pressure", "diastolic blood pressure"),
                List.of(
                        new SemanticCdeCandidateDTO("diagnosis.ttl", List.of(
                                "Clinical data element: primary diagnosis and disease classification"
                        ), 0.5, true),
                        new SemanticCdeCandidateDTO("blood-pressure.ttl", List.of(
                                "Clinical data element: systolic arterial blood pressure",
                                "Clinical data element: diastolic arterial blood pressure"
                        ), 0.5, true)
                )
        ));

        assertThat(scores).extracting("semanticCdePath")
                .containsExactly("blood-pressure.ttl", "diagnosis.ttl");
        assertThat(scores.get(0).accepted()).isTrue();
        assertThat(scores.get(0).semanticSimilarity())
                .isGreaterThan(scores.get(1).semanticSimilarity());
        assertThat(scores.get(1).accepted()).isFalse();
    }
}
