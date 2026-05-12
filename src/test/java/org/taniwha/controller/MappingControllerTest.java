package org.taniwha.controller;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.AsyncJobAckDTO;
import org.taniwha.dto.JobProgressDTO;
import org.taniwha.dto.MappingEnrichRequestDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.MappingSuggestResponseDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.service.jobs.MappingEnrichmentJobs;
import org.taniwha.service.mapping.MappingService;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MappingControllerTest {

    @Test
    void suggest_whenServiceReturnsNull_wrapsEmptyResponse() {
        MappingService mappingService = mock(MappingService.class);
        when(mappingService.suggestMappings(any())).thenReturn(null);

        MappingController controller = new MappingController(
                mappingService,
                new MappingEnrichmentJobs(),
                directExecutor()
        );

        MappingSuggestResponseDTO body = controller.suggest(new MappingSuggestRequestDTO()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getMessage()).isEqualTo("No suggestions produced.");
        assertThat(body.getHierarchy()).isEmpty();
    }

    @Test
    void suggest_whenServiceReturnsSuggestions_reportsSuccessMessage() {
        MappingService mappingService = mock(MappingService.class);
        List<Map<String, SuggestedMappingDTO>> hierarchy = List.of(Collections.emptyMap());
        when(mappingService.suggestMappings(any())).thenReturn(hierarchy);

        MappingController controller = new MappingController(
                mappingService,
                new MappingEnrichmentJobs(),
                directExecutor()
        );

        MappingSuggestResponseDTO body = controller.suggest(new MappingSuggestRequestDTO()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Suggestions computed.");
        assertThat(body.getHierarchy()).containsExactlyElementsOf(hierarchy);
    }

    @Test
    void enrich_whenHierarchyIsMissing_returnsNothingToEnrich() {
        MappingController controller = new MappingController(
                mock(MappingService.class),
                new MappingEnrichmentJobs(),
                directExecutor()
        );

        MappingSuggestResponseDTO body = (MappingSuggestResponseDTO) controller.enrich(new MappingEnrichRequestDTO()).getBody();

        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getMessage()).isEqualTo("Nothing to enrich.");
        assertThat(body.getHierarchy()).isEmpty();
    }

    @Test
    void enrich_whenExecutorAccepts_runsJobAndReturnsAcceptedAck() {
        MappingService mappingService = mock(MappingService.class);
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();
        MappingController controller = new MappingController(mappingService, jobs, directExecutor());

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        List<Map<String, SuggestedMappingDTO>> hierarchy = List.of(Map.of("field", mapping));
        MappingEnrichRequestDTO req = new MappingEnrichRequestDTO();
        req.setHierarchy(hierarchy);
        req.setSchema("{\"type\":\"object\"}");

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<Map<String, SuggestedMappingDTO>> passedHierarchy = invocation.getArgument(0, List.class);
            return passedHierarchy;
        }).when(mappingService).enrichHierarchy(any(), anyString(), any());

        AsyncJobAckDTO ack = (AsyncJobAckDTO) controller.enrich(req).getBody();

        assertThat(ack).isNotNull();
        assertThat(ack.isProgress()).isTrue();
        assertThat(ack.getMessage()).isEqualTo("Enrichment started.");

        JobProgressDTO status = controller.enrichStatus(ack.getJobId()).getBody();
        assertThat(status).isNotNull();
        assertThat(status.getState()).isEqualTo("DONE");
        assertThat(status.getPercent()).isEqualTo(100);

        MappingSuggestResponseDTO result = controller.enrichResult(ack.getJobId()).getBody();
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Enrichment completed.");
        assertThat(result.getHierarchy()).containsExactlyElementsOf(hierarchy);
    }

    @Test
    void enrich_whenExecutorRejects_returnsBusyResponse() {
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();
        ExecutorService executor = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("busy")).when(executor).submit(any(Runnable.class));

        MappingController controller = new MappingController(
                mock(MappingService.class),
                jobs,
                executor
        );

        MappingEnrichRequestDTO req = new MappingEnrichRequestDTO();
        req.setHierarchy(List.of(Map.of()));

        AsyncJobAckDTO body = (AsyncJobAckDTO) controller.enrich(req).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo("Server busy.");
        JobProgressDTO status = controller.enrichStatus(body.getJobId()).getBody();
        assertThat(status).isNotNull();
        assertThat(status.getState()).isEqualTo("FAILED");
        assertThat(status.getMessage()).isEqualTo("Server busy. Try again.");
    }

    @Test
    void enrichStatus_whenJobUnknown_returnsFailedDto() {
        MappingController controller = new MappingController(
                mock(MappingService.class),
                new MappingEnrichmentJobs(),
                directExecutor()
        );

        JobProgressDTO body = controller.enrichStatus("missing").getBody();

        assertThat(body).isNotNull();
        assertThat(body.getState()).isEqualTo("FAILED");
        assertThat(body.getPercent()).isEqualTo(0);
        assertThat(body.getMessage()).isEqualTo("Unknown jobId.");
    }

    @Test
    void enrichStatus_clampsPercentIntoRange() {
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();
        String jobId = jobs.createJob();
        jobs.update(jobId, 999, "Almost there");

        MappingController controller = new MappingController(
                mock(MappingService.class),
                jobs,
                directExecutor()
        );

        JobProgressDTO body = controller.enrichStatus(jobId).getBody();

        assertThat(body).isNotNull();
        assertThat(body.getPercent()).isEqualTo(100);
        assertThat(body.getMessage()).isEqualTo("Almost there");
    }

    @Test
    void enrichResult_handlesUnknownFailedPendingAndDoneStates() {
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();
        MappingController controller = new MappingController(
                mock(MappingService.class),
                jobs,
                directExecutor()
        );

        MappingSuggestResponseDTO unknown = controller.enrichResult("missing").getBody();
        assertThat(unknown).isNotNull();
        assertThat(unknown.isSuccess()).isFalse();
        assertThat(unknown.getMessage()).isEqualTo("Unknown jobId.");

        String failedId = jobs.createJob();
        jobs.fail(failedId, "boom");
        MappingSuggestResponseDTO failed = controller.enrichResult(failedId).getBody();
        assertThat(failed).isNotNull();
        assertThat(failed.getMessage()).isEqualTo("boom");

        String pendingId = jobs.createJob();
        jobs.start(pendingId, "Working");
        MappingSuggestResponseDTO pending = controller.enrichResult(pendingId).getBody();
        assertThat(pending).isNotNull();
        assertThat(pending.getMessage()).isEqualTo("Not ready.");

        String doneId = jobs.createJob();
        MappingSuggestResponseDTO result = new MappingSuggestResponseDTO(true, "done", List.of());
        jobs.complete(doneId, result);
        MappingSuggestResponseDTO done = controller.enrichResult(doneId).getBody();
        assertThat(done).isSameAs(result);
        assertThat(jobs.getJob(doneId)).isNull();
    }

    @Test
    void enrich_whenServiceThrows_marksJobFailed() {
        MappingService mappingService = mock(MappingService.class);
        when(mappingService.enrichHierarchy(any(), any(), any()))
                .thenThrow(new IllegalStateException("bad data"));

        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();
        MappingController controller = new MappingController(mappingService, jobs, directExecutor());

        MappingEnrichRequestDTO req = new MappingEnrichRequestDTO();
        req.setHierarchy(List.of(Map.of("field", new SuggestedMappingDTO())));

        AsyncJobAckDTO ack = (AsyncJobAckDTO) controller.enrich(req).getBody();
        assertThat(ack).isNotNull();

        JobProgressDTO status = controller.enrichStatus(ack.getJobId()).getBody();
        assertThat(status).isNotNull();
        assertThat(status.getState()).isEqualTo("FAILED");
        assertThat(status.getMessage()).isEqualTo("Enrichment failed: bad data");
    }

    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            private boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }
}
