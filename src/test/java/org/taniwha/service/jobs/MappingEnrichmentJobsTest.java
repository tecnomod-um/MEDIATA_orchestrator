package org.taniwha.service.jobs;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.MappingSuggestResponseDTO;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MappingEnrichmentJobsTest {

    @Test
    void createStartUpdateCompleteAndClear_followExpectedLifecycle() {
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();

        String jobId = jobs.createJob();
        MappingEnrichmentJobs.JobState created = jobs.getJob(jobId);
        assertThat(created).isNotNull();
        assertThat(created.state).isEqualTo("QUEUED");
        assertThat(created.percent.get()).isZero();
        assertThat(created.message).isEqualTo("Queued…");

        jobs.start(jobId, null);
        assertThat(created.state).isEqualTo("RUNNING");
        assertThat(created.percent.get()).isEqualTo(1);
        assertThat(created.message).isEqualTo("Running…");

        jobs.update(jobId, 72, "Working");
        assertThat(created.percent.get()).isEqualTo(72);
        assertThat(created.message).isEqualTo("Working");

        MappingSuggestResponseDTO result = new MappingSuggestResponseDTO(true, "done", List.of());
        jobs.complete(jobId, result);
        assertThat(created.state).isEqualTo("DONE");
        assertThat(created.percent.get()).isEqualTo(100);
        assertThat(created.message).isEqualTo("Done.");
        assertThat(created.result).isSameAs(result);

        jobs.clear(jobId);
        assertThat(jobs.getJob(jobId)).isNull();
    }

    @Test
    void failAndUpdate_clampValuesAndPreserveFallbackMessages() {
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();
        String jobId = jobs.createJob();

        jobs.update(jobId, -12, null);
        MappingEnrichmentJobs.JobState state = jobs.getJob(jobId);
        assertThat(state).isNotNull();
        assertThat(state.percent.get()).isZero();
        assertThat(state.message).isEqualTo("Queued…");

        jobs.fail(jobId, null);
        assertThat(state.state).isEqualTo("FAILED");
        assertThat(state.message).isEqualTo("Failed.");
        assertThat(state.percent.get()).isZero();
    }

    @Test
    void unknownJobOperations_areIgnoredSafely() {
        MappingEnrichmentJobs jobs = new MappingEnrichmentJobs();

        jobs.start("missing", "msg");
        jobs.update("missing", 50, "msg");
        jobs.fail("missing", "msg");
        jobs.complete("missing", new MappingSuggestResponseDTO(true, "done", List.of()));
        jobs.clear("missing");

        assertThat(jobs.getJob("missing")).isNull();
    }
}
