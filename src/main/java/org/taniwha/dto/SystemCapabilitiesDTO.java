package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SystemCapabilitiesDTO {
    private boolean semanticIntegration;
    private boolean hl7fhir;

    public SystemCapabilitiesDTO(boolean semanticIntegration, boolean hl7fhir) {
        this.semanticIntegration = semanticIntegration;
        this.hl7fhir = hl7fhir;
    }
}
