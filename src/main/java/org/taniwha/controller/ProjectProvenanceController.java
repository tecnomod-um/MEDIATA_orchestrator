package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.model.ProjectProvenanceRecord;
import org.taniwha.service.ProjectProvenanceService;

import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project provenance", description = "Read the project activity journal and its PROV-O representation.")
public class ProjectProvenanceController {

    private final ProjectProvenanceService provenanceService;

    public ProjectProvenanceController(ProjectProvenanceService provenanceService) {
        this.provenanceService = provenanceService;
    }

    @Operation(summary = "List project activity records (administrator only)")
    @GetMapping("/activity")
    public ResponseEntity<Page<ProjectProvenanceRecord>> listActivity(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        return ResponseEntity.ok(provenanceService.listActivity(projectId, page, size, authentication));
    }

    @Operation(summary = "Get one project activity record (administrator only)")
    @GetMapping("/activity/{eventId}")
    public ResponseEntity<ProjectProvenanceRecord> getActivity(
            @PathVariable String projectId,
            @PathVariable String eventId,
            Authentication authentication) {
        return ResponseEntity.ok(provenanceService.getActivity(projectId, eventId, authentication));
    }

    @Operation(summary = "Export project provenance as PROV-O JSON-LD")
    @GetMapping(value = "/provenance", produces = "application/ld+json")
    public ResponseEntity<Map<String, Object>> exportProvenance(
            @PathVariable String projectId,
            Authentication authentication) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/ld+json"))
                .body(provenanceService.exportProvO(projectId, authentication));
    }

}
