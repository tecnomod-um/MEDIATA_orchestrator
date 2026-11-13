package org.taniwha.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.taniwha.model.NodeProvenanceEvent;

import java.util.Optional;

@Repository
public interface NodeProvenanceEventRepository extends MongoRepository<NodeProvenanceEvent, String> {
    Optional<NodeProvenanceEvent> findByNodeIdAndSourceEventId(String nodeId, String sourceEventId);
}
