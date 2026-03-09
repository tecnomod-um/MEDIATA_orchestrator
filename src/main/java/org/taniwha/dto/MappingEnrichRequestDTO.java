package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class MappingEnrichRequestDTO {
    private List<Map<String, SuggestedMappingDTO>> hierarchy;
    private String schema;
}
