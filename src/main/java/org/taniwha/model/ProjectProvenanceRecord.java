package org.taniwha.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Document(collection = "project_provenance_log")
@CompoundIndexes({
        @CompoundIndex(name = "project_occurred_idx", def = "{'projectId': 1, 'occurredAt': -1}"),
        @CompoundIndex(name = "project_activity_idx", def = "{'projectId': 1, 'activityId': 1, 'occurredAt': 1}"),
        @CompoundIndex(name = "project_event_idx", def = "{'projectId': 1, 'eventType': 1, 'occurredAt': -1}"),
        @CompoundIndex(name = "project_node_source_event_unique_idx",
                def = "{'projectId': 1, 'nodeId': 1, 'sourceEventId': 1}", unique = true, sparse = true)
})
public class ProjectProvenanceRecord {

    public enum EventType {
        PROJECT_NODE_ADDED,
        PROJECT_NODE_REMOVED,
        USER_NODE_ACCESS,
        DATASET_HARMONIZATION
    }

    public enum Outcome {
        STARTED,
        SUCCEEDED,
        FAILED
    }

    public enum ActorType {
        USER,
        NODE,
        SYSTEM
    }

    @Id
    private String id;
    private String projectId;
    private String activityId;
    private String sourceEventId;
    private EventType eventType;
    private Outcome outcome;
    private Instant occurredAt;
    private Instant recordedAt;
    private ActorType actorType;
    private String actorId;
    private String nodeId;
    private String nodeName;
    private String nodeJobId;
    private List<String> sourceDatasetIds = new ArrayList<>();
    private List<String> generatedDatasetIds = new ArrayList<>();
    private String mappingSpecId;
    private String message;
}
