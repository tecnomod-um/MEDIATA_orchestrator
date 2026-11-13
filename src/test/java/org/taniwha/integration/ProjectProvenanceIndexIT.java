package org.taniwha.integration;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.taniwha.model.ProjectProvenanceRecord;
import org.taniwha.repository.ProjectProvenanceRepository;

import java.time.Instant;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectProvenanceIndexIT extends BaseIntegrationTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private ProjectProvenanceRepository repository;

    @Test
    void uniqueSourceEventIndexIgnoresRecordsWithoutSourceEventIds() {
        Document index = StreamSupport.stream(
                        mongoTemplate.getCollection("project_provenance_log")
                                .listIndexes().spliterator(), false)
                .filter(candidate -> "project_node_source_event_unique_idx"
                        .equals(candidate.getString("name")))
                .findFirst()
                .orElseThrow();

        assertThat(index.get("partialFilterExpression", Document.class))
                .isEqualTo(new Document("sourceEventId", new Document("$type", "string")));

        repository.save(accessRecord());
        repository.save(accessRecord());
    }

    private ProjectProvenanceRecord accessRecord() {
        ProjectProvenanceRecord record = new ProjectProvenanceRecord();
        record.setProjectId("index-test-project");
        record.setActivityId(java.util.UUID.randomUUID().toString());
        record.setEventType(ProjectProvenanceRecord.EventType.USER_NODE_ACCESS);
        record.setOutcome(ProjectProvenanceRecord.Outcome.SUCCEEDED);
        record.setOccurredAt(Instant.now());
        record.setRecordedAt(Instant.now());
        record.setActorType(ProjectProvenanceRecord.ActorType.USER);
        record.setActorId("index-test-user");
        record.setNodeId("index-test-node");
        return record;
    }
}
