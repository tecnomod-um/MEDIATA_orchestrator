package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FieldMetadataDTO {
    private String name;
    private boolean optional;
    private String type;

    public FieldMetadataDTO() {
    }

    public FieldMetadataDTO(String name, boolean optional, String type) {
        this.name = name;
        this.optional = optional;
        this.type = type;
    }
}
