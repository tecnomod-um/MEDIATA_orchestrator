package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.dto.SimilarColumnScoreDTO;
import org.taniwha.dto.SimilarColumnsRequestDTO;
import org.taniwha.service.mapping.SimilarColumnsService;

import java.util.List;

@RestController
@RequestMapping("/api/mappings")
@Tag(name = "Mapping assistance")
public class SimilarColumnsController {
    private final SimilarColumnsService similarColumnsService;

    public SimilarColumnsController(SimilarColumnsService similarColumnsService) {
        this.similarColumnsService = similarColumnsService;
    }

    @Operation(summary = "Rank loaded source columns by semantic similarity to one source column")
    @PostMapping("/similar-columns")
    public ResponseEntity<List<SimilarColumnScoreDTO>> rank(@RequestBody SimilarColumnsRequestDTO request) {
        if (request == null || request.getAnchor() == null || request.getCandidates() == null
                || request.getCandidates().size() > 250) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(similarColumnsService.rank(request));
    }
}
