package org.taniwha.service.jobs;

import org.springframework.stereotype.Service;
import org.taniwha.dto.MappingSuggestResponseDTO;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MappingEnrichmentJobs {

    public static final class JobState {
        public final String jobId;
        public volatile String state = "QUEUED";

        public final AtomicInteger percent = new AtomicInteger(0);

        public volatile String message = "Queued…";

        public volatile MappingSuggestResponseDTO result = null;

        JobState(String jobId) { this.jobId = jobId; }
    }

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    public String createJob() {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new JobState(id));
        return id;
    }

    public JobState getJob(String jobId) {
        return jobs.get(jobId);
    }

    public void clear(String jobId) {
        jobs.remove(jobId);
    }

    public void start(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.state = "RUNNING";
        s.message = message != null ? message : "Running…";
        s.percent.set(Math.max(s.percent.get(), 1));
    }

    public void update(String jobId, int percent, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.percent.set(Math.max(0, Math.min(100, percent)));
        if (message != null) s.message = message;
    }

    public void fail(String jobId, String message) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.state = "FAILED";
        s.message = message != null ? message : "Failed.";
        s.percent.set(Math.max(0, Math.min(100, s.percent.get())));
    }

    public void complete(String jobId, MappingSuggestResponseDTO result) {
        JobState s = jobs.get(jobId);
        if (s == null) return;
        s.state = "DONE";
        s.result = result;
        s.percent.set(100);
        s.message = "Done.";
    }
}
