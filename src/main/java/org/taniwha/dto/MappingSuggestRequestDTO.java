package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MappingSuggestRequestDTO {
    private List<ColumnInFileDTO> elementFiles;
    private String schema;
}
