package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SuggestedRefDTO {
    private String groupKey;     // `${nodeId}::${fileName}::${groupColumn}`
    private String groupColumn;  // source column name
    private String fileName;     // source file name
    private String nodeId;       // source node id
    private Object value;
    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }
}
