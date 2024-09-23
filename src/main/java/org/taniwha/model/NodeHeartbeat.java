package org.taniwha.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NodeHeartbeat {
    private String nodeId;
    private long timestamp;
}
