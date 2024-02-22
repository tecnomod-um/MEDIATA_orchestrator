package org.taniwha.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.taniwha.dto.AnalyticsResponseDTO;
import org.taniwha.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/data")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Autowired
    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostMapping("/process")
    public CompletableFuture<ResponseEntity<AnalyticsResponseDTO>> processAnalytics(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty())
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(new AnalyticsResponseDTO("File is empty")));
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.endsWith(".csv")) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(415).body(new AnalyticsResponseDTO("Unsupported file type"))
            );
        }

        System.out.println("Processing file: " + file.getOriginalFilename());
        // Call the async service and then apply the response from the service
        return analyticsService.processAnalytics(file)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new AnalyticsResponseDTO("Error processing file: " + ex.getMessage())));
    }

}
