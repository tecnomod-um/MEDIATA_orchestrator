package org.taniwha.service.jobs;

import org.taniwha.dto.MappingSuggestResponseDTO;
import java.util.concurrent.atomic.AtomicInteger;

public final class MappingEnrichmentJobState {
    public final String jobId;
    public volatile String state = "QUEUED";

    public final AtomicInteger percent = new AtomicInteger(0);

    public volatile String message = "Queued…";

    public volatile MappingSuggestResponseDTO result = null;

    MappingEnrichmentJobState(String jobId) { this.jobId = jobId; }
}
