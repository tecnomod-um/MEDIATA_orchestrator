package org.taniwha.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NodeSummary {

    private String nodeId;
    private String name;
    private String description;
    private String color;
    private String serviceUrl;
    private boolean proxyRequired;
    private String proxyBasePath;

    public NodeSummary(String nodeId, String name, String description, String color, String serviceUrl) {
        this.nodeId = nodeId;
        this.name = name;
        this.description = description;
        this.color = color;
        this.serviceUrl = serviceUrl;
    }
}
