package org.taniwha.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Document(collection = "node_provenance_inbox")
@CompoundIndex(name = "node_source_event_unique_idx",
        def = "{'nodeId': 1, 'sourceEventId': 1}", unique = true)
public class NodeProvenanceEvent {

    @Id
    private String id;
    private int schemaVersion;
    private String nodeId;
    private String nodeName;
    private String sourceEventId;
    private Instant occurredAt;
    private Instant receivedAt;
    private List<String> sourceDatasetIds = new ArrayList<>();
    private String outputDatasetId;
    private String mappingSpecId;
}
