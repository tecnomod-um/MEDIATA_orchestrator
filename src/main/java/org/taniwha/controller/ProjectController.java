package org.taniwha.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.ProjectDTO;
import org.taniwha.dto.SaveProjectRequestDTO;
import org.taniwha.service.ProjectService;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping("/list")
    public ResponseEntity<List<ProjectDTO>> listProjects() {
        return ResponseEntity.ok(projectService.listProjects());
    }

    @PostMapping("/save")
    public ResponseEntity<ProjectDTO> saveProject(@RequestBody SaveProjectRequestDTO req) {
        return ResponseEntity.ok(projectService.saveProject(req));
    }
}
