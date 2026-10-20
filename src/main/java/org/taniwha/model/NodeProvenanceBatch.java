package org.taniwha.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class NodeProvenanceBatch {
    private String nodeId;
    private int schemaVersion;
    private List<NodeProvenanceActivity> activities = new ArrayList<>();
}
