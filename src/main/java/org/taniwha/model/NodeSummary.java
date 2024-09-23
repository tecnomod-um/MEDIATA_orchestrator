package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class NodeSummary {

    private String nodeId;
    private String name;
    private String description;
    private String color;
}
