package org.taniwha.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.SystemCapabilitiesDTO;
import org.taniwha.service.SystemService;

// System-related status petitions
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final SystemService systemService;

    public SystemController(SystemService systemCapabilitiesService) {
        this.systemService = systemCapabilitiesService;
    }

    @GetMapping("/capabilities")
    public ResponseEntity<SystemCapabilitiesDTO> capabilities() {
        return ResponseEntity.ok(systemService.getCapabilities());
    }
}
