package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SimilarColumnsRequestDTO {
    private ColumnInFileDTO anchor;
    private List<ColumnInFileDTO> candidates;
}
