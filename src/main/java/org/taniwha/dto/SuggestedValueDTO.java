package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SuggestedValueDTO {
    private String name;                   // standardized value
    private String terminology;
    private String description;
    private List<SuggestedRefDTO> mapping; // source references
}
