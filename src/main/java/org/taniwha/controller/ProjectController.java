package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.ProjectDTO;
import org.taniwha.dto.SaveProjectRequestDTO;
import org.taniwha.service.ProjectService;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects", description = "List and save federated-analysis project definitions.")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Operation(summary = "List saved projects")
    @GetMapping("/list")
    public ResponseEntity<List<ProjectDTO>> listProjects() {
        return ResponseEntity.ok(projectService.listProjects());
    }

    @Operation(summary = "Save a project definition")
    @PostMapping("/save")
    public ResponseEntity<ProjectDTO> saveProject(@RequestBody SaveProjectRequestDTO req) {
        return ResponseEntity.ok(projectService.saveProject(req));
    }
}
