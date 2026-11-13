package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.SystemCapabilitiesDTO;
import org.taniwha.service.SystemService;

// System-related status petitions
@RestController
@RequestMapping("/api/system")
@Tag(name = "System", description = "Inspect optional service capabilities available to the frontend.")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemCapabilitiesService) {
        this.systemService = systemCapabilitiesService;
    }

    @Operation(summary = "Get optional service capabilities")
    @GetMapping("/capabilities")
    public ResponseEntity<SystemCapabilitiesDTO> capabilities() {
        return ResponseEntity.ok(systemService.getCapabilities());
    }
}
