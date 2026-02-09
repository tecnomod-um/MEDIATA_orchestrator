package org.taniwha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.SystemCapabilitiesDTO;

@Service
public class SystemService {
    @Value("${python.launcher.enabled:true}")
    private boolean rdfBuilderEnabled;

    @Value("${fhir.launcher.enabled:true}")
    private boolean fhirEnabled;

    public SystemCapabilitiesDTO getCapabilities() {
        return new SystemCapabilitiesDTO(
                rdfBuilderEnabled,
                fhirEnabled
        );
    }
}
