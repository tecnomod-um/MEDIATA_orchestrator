package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeSummary;
import org.taniwha.service.NodeAccessService;
import org.taniwha.service.NodeService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Requests related user interactions with the registered nodes
@RestController
@RequestMapping("/nodes/connect")
public class NodeRetrieverController {

    private static final Logger logger = LoggerFactory.getLogger(NodeRetrieverController.class);
    private final NodeService nodeService;
    private final NodeAccessService nodeAccessService;

    private static final String ERROR = "error";

    public NodeRetrieverController(NodeService nodeService, NodeAccessService nodeAccessService) {
        this.nodeService = nodeService;
        this.nodeAccessService = nodeAccessService;
    }

    @GetMapping("/list")
    public ResponseEntity<List<NodeSummary>> listNodes() {
        List<NodeSummary> nodes = nodeService.getNodeSummaries();
        return ResponseEntity.ok(nodes);
    }

    @GetMapping("/info/{nodeId}")
    public ResponseEntity<Map<String, Object>> getNodeInfo(@PathVariable String nodeId, @RequestHeader("Authorization") String userToken, @RequestHeader("Kerberos-TGT") String userTgt) {
        String jwtToken = userToken.substring(7);
        NodeInfo nodeInfo = nodeService.findNodeById(nodeId);
        if (nodeInfo == null || nodeInfo.getNodeId() == null) {
            logger.warn("Node not found.");
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put(ERROR, "Node not found");
            return ResponseEntity.status(404).body(errorResponse);
        }

        boolean hasAccess = nodeAccessService.checkUserAccess(nodeId, jwtToken);
        if (!hasAccess) {
            logger.warn("User requested access to node without permission (nodeID: {})", nodeId);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put(ERROR, "You do not have the required permissions to access this node.");
            return ResponseEntity.status(412).body(errorResponse);
        }

        Map<String, Object> result = nodeAccessService.getServiceToken(nodeId, userTgt);
        if (result.containsKey("token")) {
            logger.debug("Token generated and sent successfully for node ID: {}", nodeId);
            result.put("nodeInfo", nodeInfo);
            return ResponseEntity.ok(result);
        } else {
            String errorMessage = result.get(ERROR).toString();
            if (errorMessage.contains("Failed to connect to the node")) {
                logger.error("Error generating or sending token: {}", errorMessage);
                return ResponseEntity.status(502).body(result);
            } else {
                logger.error("Error generating or sending token: {}", errorMessage);
                result.put(ERROR, errorMessage);
                return ResponseEntity.status(500).body(result);
            }
        }
    }
}
