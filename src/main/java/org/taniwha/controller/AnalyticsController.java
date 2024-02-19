package org.taniwha.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
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
        // Check if the file is valid
        if (file.isEmpty())
            return ResponseEntity.badRequest().body(new AnalyticsResponseDTO("File is empty"));
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".csv"))
            return ResponseEntity.status(415).body(new AnalyticsResponseDTO("Unsupported file type"));

        System.out.println("Processing file: " + file.getOriginalFilename());
        AnalyticsResponseDTO response = analyticsService.processAnalytics(file);
        return ResponseEntity.ok(response);
    }
}
