package org.taniwha.controller;

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
public class FHIRController {

    private static final Logger logger = LoggerFactory.getLogger(FHIRController.class);
    private final FHIRService fhirService;

    public FHIRController(FHIRService fhirService) {
        this.fhirService = fhirService;
    }

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
