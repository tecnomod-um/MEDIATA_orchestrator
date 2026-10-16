package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.dto.SemanticCdeCandidateScoreDTO;
import org.taniwha.dto.SemanticCdeScoringRequestDTO;
import org.taniwha.service.SemanticCdeScoringService;

import java.util.List;

@RestController
@RequestMapping("/api/semantic-cde")
@Tag(name = "Semantic CDE", description = "Score registry candidates for semantic CDE matching.")
public class SemanticCdeScoringController {

    private final SemanticCdeScoringService scoringService;

    public SemanticCdeScoringController(SemanticCdeScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @Operation(summary = "Score semantic CDE registry candidates")
    @PostMapping("/score-candidates")
    public ResponseEntity<List<SemanticCdeCandidateScoreDTO>> scoreCandidates(
            @RequestBody SemanticCdeScoringRequestDTO request
    ) {
        return ResponseEntity.ok(scoringService.scoreCandidates(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> invalidRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
