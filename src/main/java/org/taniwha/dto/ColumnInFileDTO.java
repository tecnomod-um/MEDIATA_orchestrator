package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ColumnInFileDTO {
    private String nodeId;
    private String fileName;
    private String column;
    private List<String> values;
    private String color;
    private String terminology;
    private Map<String, String> valueTerminologies;
}
