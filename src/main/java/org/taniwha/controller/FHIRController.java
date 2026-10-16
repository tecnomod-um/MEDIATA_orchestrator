package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.service.FHIRService;

import java.io.IOException;

@RestController
@RequestMapping("/fhir")
@Tag(name = "FHIR", description = "Generate FHIR representations from clustering results.")
public class FHIRController {

    private static final Logger logger = LoggerFactory.getLogger(FHIRController.class);
    private final FHIRService fhirService;

    public FHIRController(FHIRService fhirService) {
        this.fhirService = fhirService;
    }

    @Operation(summary = "Generate FHIR resources from cluster data")
    @PostMapping("/clusters")
    public ResponseEntity<String> createClusters(@RequestBody String jsonText) {
        logger.debug("Received clusters payload ({} bytes)", jsonText.length());
        try {
            String responseJson = fhirService.processClusters(jsonText);
            return ResponseEntity.ok(responseJson);
        } catch (IOException e) {
            logger.error("Error reading response file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\":\"Could not load cluster response\"}");
        }
    }
}
