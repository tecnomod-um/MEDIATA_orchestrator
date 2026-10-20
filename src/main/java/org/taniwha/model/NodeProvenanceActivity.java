package org.taniwha.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class NodeProvenanceActivity {
    private String activityId;
    private String outputDatasetId;
    private List<String> sourceDatasetIds = new ArrayList<>();
    private String mappingSpecId;
    private String generatedAt;
}
