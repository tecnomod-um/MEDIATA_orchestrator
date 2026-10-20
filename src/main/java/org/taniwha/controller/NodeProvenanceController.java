package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeProvenanceBatch;
import org.taniwha.model.NodeProvenanceBatchResponse;
import org.taniwha.service.NodeService;
import org.taniwha.service.ProjectProvenanceService;
import org.taniwha.util.JwtTokenUtil;

import java.util.List;

@RestController
@Tag(name = "Node provenance", description = "Synchronize durable provenance facts from a MEDIATA node.")
public class NodeProvenanceController {

    private static final int MAX_BATCH_SIZE = 100;

    private final NodeService nodeService;
    private final ProjectProvenanceService provenanceService;
    private final JwtTokenUtil jwtTokenUtil;

    public NodeProvenanceController(NodeService nodeService,
                                    ProjectProvenanceService provenanceService,
                                    JwtTokenUtil jwtTokenUtil) {
        this.nodeService = nodeService;
        this.provenanceService = provenanceService;
        this.jwtTokenUtil = jwtTokenUtil;
    }

    @Operation(summary = "Synchronize a batch of node provenance events")
    @PostMapping("/nodes/provenance/batch")
    public ResponseEntity<NodeProvenanceBatchResponse> synchronize(
            @RequestBody NodeProvenanceBatch batch,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (batch == null || batch.getSchemaVersion() != 1
                || batch.getNodeId() == null || batch.getNodeId().isBlank()
                || batch.getActivities() == null || batch.getActivities().size() > MAX_BATCH_SIZE) {
            return ResponseEntity.badRequest().body(new NodeProvenanceBatchResponse(List.of()));
        }
        NodeInfo node = nodeService.findNodeById(batch.getNodeId());
        if (node == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new NodeProvenanceBatchResponse(List.of()));
        }
        if (!isValidNodeToken(authorization, batch.getNodeId(), node.getPublicKey())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new NodeProvenanceBatchResponse(List.of()));
        }

        List<String> acknowledged = provenanceService.ingestNodeProvenance(
                batch.getNodeId(), node.getName(), batch.getActivities());
        return ResponseEntity.ok(new NodeProvenanceBatchResponse(acknowledged));
    }

    private boolean isValidNodeToken(String authorization, String nodeId, String publicKey) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        try {
            return jwtTokenUtil.validateNodeSyncToken(
                    authorization.substring(7), nodeId, "/nodes/provenance/batch", publicKey);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
