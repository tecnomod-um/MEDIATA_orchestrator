package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SuggestedMappingDTO {
    private String mappingType;   // "standard" or "one-hot"
    private String fileName;      // "suggested_mapping"
    private String nodeId;        // optional; UI makeId() uses it
    private List<String> columns; // source columns
    private String terminology;
    private String description;
    private List<SuggestedGroupDTO> groups;
}
