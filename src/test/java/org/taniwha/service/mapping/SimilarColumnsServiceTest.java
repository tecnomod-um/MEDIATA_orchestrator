package org.taniwha.service.mapping;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.SimilarColumnsRequestDTO;
import org.taniwha.service.EmbeddingService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimilarColumnsServiceTest {
    private static ColumnInFileDTO column(String name, String file, String terminology) {
        ColumnInFileDTO column = new ColumnInFileDTO();
        column.setNodeId("node");
        column.setFileName(file);
        column.setColumn(name);
        column.setValues(List.of("integer", "min:0", "max:100"));
        column.setTerminology(terminology);
        return column;
    }

    @Test
    void ranksCandidatesUsingNameAndTerminologyWithoutValueDominance() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embedColumnWithValues(contains("Toileting ability"), anyList()))
                .thenReturn(new float[]{1, 0});
        when(embeddings.embedColumnWithValues(contains("Bathroom assistance"), anyList()))
                .thenReturn(new float[]{0.9f, 0.1f});
        when(embeddings.embedColumnWithValues(contains("Food intake"), anyList()))
                .thenReturn(new float[]{0, 1});

        ColumnInFileDTO anchor = column("Toilet", "a.csv", "Toileting ability | 284899001");
        ColumnInFileDTO related = column("Bathroom assistance", "b.csv", "");
        SimilarColumnsRequestDTO request = new SimilarColumnsRequestDTO();
        request.setAnchor(anchor);
        request.setCandidates(List.of(anchor, column("Food intake", "c.csv", ""), related));

        var result = new SimilarColumnsService(embeddings).rank(request);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).column()).isEqualTo("Bathroom assistance");
        assertThat(result.get(0).score()).isGreaterThan(result.get(1).score());
        verify(embeddings).embedColumnWithValues(contains("Toileting ability"), eq(List.of()));
    }

    @Test
    void returnsNoScoresWhenEmbeddingModelIsUnavailable() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        when(embeddings.embedColumnWithValues(contains("Toilet"), anyList())).thenReturn(new float[]{0, 0});
        SimilarColumnsRequestDTO request = new SimilarColumnsRequestDTO();
        request.setAnchor(column("Toilet", "a.csv", ""));
        request.setCandidates(List.of(column("Bathroom", "b.csv", "")));
        assertThat(new SimilarColumnsService(embeddings).rank(request)).isEmpty();
    }
}
