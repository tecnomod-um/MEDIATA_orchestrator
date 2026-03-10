package org.taniwha.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.*;
import org.taniwha.service.mapping.MappingService;
import org.taniwha.service.jobs.MappingEnrichmentJobs;
import java.util.concurrent.RejectedExecutionException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@RestController
@RequestMapping("/api/mappings")
public class MappingController {

    private final MappingService mappingService;
    private final MappingEnrichmentJobs enrichmentJobs;
    private final ExecutorService enrichExecutor;

    public MappingController(
            MappingService mappingService,
            MappingEnrichmentJobs enrichmentJobs,
            @Qualifier("enrichExecutor") ExecutorService enrichExecutor
    ) {
        this.mappingService = mappingService;
        this.enrichmentJobs = enrichmentJobs;
        this.enrichExecutor = enrichExecutor;
    }

    @PostMapping("/suggest")
    public ResponseEntity<MappingSuggestResponseDTO> suggest(@RequestBody MappingSuggestRequestDTO req) {
        List<Map<String, SuggestedMappingDTO>> hierarchy = mappingService.suggestMappings(req);
        if (hierarchy == null) hierarchy = Collections.emptyList();
        String msg = hierarchy.isEmpty() ? "No suggestions produced." : "Suggestions computed.";
        return ResponseEntity.ok(new MappingSuggestResponseDTO(true, msg, hierarchy));
    }



    @PostMapping("/enrich")
    public ResponseEntity<?> enrich(@RequestBody MappingEnrichRequestDTO req) {
        List<Map<String, SuggestedMappingDTO>> hierarchy =
                (req == null || req.getHierarchy() == null) ? Collections.emptyList() : req.getHierarchy();

        if (hierarchy.isEmpty()) {
            return ResponseEntity.ok(new MappingSuggestResponseDTO(true, "Nothing to enrich.", hierarchy));
        }

        String jobId = enrichmentJobs.createJob();

        try {
            enrichExecutor.submit(() -> runEnrichmentJob(jobId, req));
        } catch (RejectedExecutionException ex) {
            enrichmentJobs.fail(jobId, "Server busy. Try again.");
            return ResponseEntity.status(503).body(new AsyncJobAckDTO(jobId, true, "Server busy."));
        }

        return ResponseEntity.accepted().body(new AsyncJobAckDTO(jobId, true, "Enrichment started."));
    }


    @GetMapping("/enrich/status/{jobId}")
    public ResponseEntity<JobProgressDTO> enrichStatus(@PathVariable String jobId) {
        MappingEnrichmentJobs.JobState s = enrichmentJobs.getJob(jobId);
        if (s == null) {
            return ResponseEntity.status(404)
                    .body(new JobProgressDTO(jobId, "FAILED", 0, "Unknown jobId."));
        }

        return ResponseEntity.ok(new JobProgressDTO(
                s.jobId,
                s.state,
                clampPercent(s.percent.get()),
                s.message
        ));
    }

    @GetMapping("/enrich/result/{jobId}")
    public ResponseEntity<MappingSuggestResponseDTO> enrichResult(@PathVariable String jobId) {
        MappingEnrichmentJobs.JobState s = enrichmentJobs.getJob(jobId);
        if (s == null) {
            return ResponseEntity.status(404)
                    .body(new MappingSuggestResponseDTO(false, "Unknown jobId.", Collections.emptyList()));
        }

        if ("FAILED".equals(s.state)) {
            return ResponseEntity.status(500)
                    .body(new MappingSuggestResponseDTO(false, s.message, Collections.emptyList()));
        }

        if (!"DONE".equals(s.state) || s.result == null) {
            return ResponseEntity.status(202)
                    .body(new MappingSuggestResponseDTO(false, "Not ready.", Collections.emptyList()));
        }
        MappingSuggestResponseDTO result = s.result;
        enrichmentJobs.clear(jobId);
        return ResponseEntity.ok(result);
    }

    private void runEnrichmentJob(String jobId, MappingEnrichRequestDTO req) {
        try {
            enrichmentJobs.start(jobId, "Preparing enrichment…");

            List<Map<String, SuggestedMappingDTO>> out = mappingService.enrichHierarchy(
                    req.getHierarchy(),
                    req.getSchema(),
                    (percent, message) -> enrichmentJobs.update(jobId, percent, message)
            );

            enrichmentJobs.complete(jobId,
                    new MappingSuggestResponseDTO(true, "Enrichment completed.", out)
            );
        } catch (Exception e) {
            enrichmentJobs.fail(jobId, "Enrichment failed: " + e.getMessage());
        }
    }

    private int clampPercent(int p) {
        return Math.max(0, Math.min(100, p));
    }
}
