package org.taniwha.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.SystemCapabilitiesDTO;

// Retrieves which system functionality is operative in the instance
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
