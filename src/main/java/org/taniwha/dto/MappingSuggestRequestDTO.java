package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MappingSuggestRequestDTO {
    private List<ElementFileDTO> elementFiles;
    private String schema;
}
