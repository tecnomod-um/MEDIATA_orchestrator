package org.taniwha.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.taniwha.model.ProjectProvenanceRecord;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectProvenanceRepository extends MongoRepository<ProjectProvenanceRecord, String> {
    Page<ProjectProvenanceRecord> findByProjectIdOrderByOccurredAtDesc(String projectId, Pageable pageable);

    List<ProjectProvenanceRecord> findByProjectIdOrderByOccurredAtAsc(String projectId);

    Optional<ProjectProvenanceRecord> findByIdAndProjectId(String id, String projectId);

    boolean existsByProjectIdAndNodeIdAndSourceEventId(
            String projectId,
            String nodeId,
            String sourceEventId
    );
}
