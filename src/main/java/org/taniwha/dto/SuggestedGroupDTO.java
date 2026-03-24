package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SuggestedGroupDTO {
    private String column;                 // union/schema field
    private List<SuggestedValueDTO> values;
}
