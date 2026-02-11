package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@AllArgsConstructor
public class MappingSuggestResponseDTO {
    private boolean success;
    private String message;
    private List<Map<String, SuggestedMappingDTO>> hierarchy;
}
