package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SystemCapabilitiesDTO {
    private boolean semanticAlignment;
    private boolean hl7fhir;

    public SystemCapabilitiesDTO(boolean semanticAlignment, boolean hl7fhir) {
        this.semanticAlignment = semanticAlignment;
        this.hl7fhir = hl7fhir;
    }
}
