package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ElementFileDTO {
    private String nodeId;
    private String fileName;
    private String column;
    private List<String> values;
    private String color;
}
