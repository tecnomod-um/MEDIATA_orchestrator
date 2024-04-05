package org.taniwha.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/data")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Autowired
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/process")
    public ResponseEntity<AnalyticsResponseDTO> processAnalytics(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(new AnalyticsResponseDTO("File is empty"));

        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".csv"))
            return ResponseEntity.status(415).body(new AnalyticsResponseDTO("Unsupported file type"));

        System.out.println("Processing file: " + file.getOriginalFilename());

        try {
            AnalyticsResponseDTO response = analyticsService.processAnalytics(file).get();
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Error processing file: " + ex.getMessage()));
        }
    }

    @PostMapping("/reprocess")
    public ResponseEntity<AnalyticsResponseDTO> recalculateFeature(@RequestParam("file") MultipartFile file, @RequestParam("featureName") String featureName, @RequestParam("featureType") String featureType) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(new AnalyticsResponseDTO("File is empty"));
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".csv"))
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(new AnalyticsResponseDTO("Unsupported file type"));
        if (!featureType.equalsIgnoreCase("continuous") && !featureType.equalsIgnoreCase("categorical"))
            return ResponseEntity.badRequest().body(new AnalyticsResponseDTO("Invalid feature type"));

        try {
            AnalyticsResponseDTO response = analyticsService.recalculateFeatureAsType(file, featureName, featureType).get();
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new AnalyticsResponseDTO("Error processing file: " + ex.getMessage()));
        }
    }
}
