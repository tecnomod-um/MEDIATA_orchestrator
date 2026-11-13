package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class NodeProvenanceBatchResponse {
    private List<String> acknowledgedEventIds;
}
