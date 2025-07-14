package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.dto.NodeDTO;
import org.taniwha.dto.RegisterResponseDTO;
import org.taniwha.model.NodeHeartbeat;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeService;

import java.io.File;
import java.io.IOException;
import java.util.Base64;

// Requests related to node registration and presence in the system
@RestController
public class NodeManagerController {

    private static final Logger logger = LoggerFactory.getLogger(NodeManagerController.class);

    private final NodeService nodeService;

    @Autowired
    public NodeManagerController(NodeService nodeService) {
        this.nodeService = nodeService;
    }

    @PostMapping("/nodes/register")
    public ResponseEntity<RegisterResponseDTO> registerNode(@RequestBody NodeDTO node) {
        if (node.getIp() == null) {
            logger.error("Invalid node information: {}", node);
            RegisterResponseDTO responseDTO = new RegisterResponseDTO();
            responseDTO.setMessage("Invalid node information");
            return ResponseEntity.badRequest().body(responseDTO);
        }
        try {
            NodeInfo nodeInfo = new NodeInfo(
                    node.getNodeId(),
                    node.getIp(),
                    node.getName(),
                    node.getPassword(),
                    node.getDescription(),
                    node.getColor(),
                    node.getPublicKey()
            );

            String keytabPath = nodeService.registerNode(nodeInfo);
            logger.info("New node registered: {} ({})", node.getName(), node.getNodeId());
            RegisterResponseDTO responseDTO = new RegisterResponseDTO();
            responseDTO.setMessage("Node registered successfully");
            String keytabContent = encodeKeytabToBase64(keytabPath);
            responseDTO.setKeytab(keytabContent);

            return ResponseEntity.ok(responseDTO);
        } catch (RuntimeException e) {
            logger.error("Error registering node in service", e);
            RegisterResponseDTO responseDTO = new RegisterResponseDTO();
            responseDTO.setMessage(e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(responseDTO);
        }
    }

    private String encodeKeytabToBase64(String keytabPath) {
        try {
            File keytabFile = new File(keytabPath);
            byte[] keytabBytes = java.nio.file.Files.readAllBytes(keytabFile.toPath());
            return Base64.getEncoder().encodeToString(keytabBytes);
        } catch (IOException e) {
            logger.error("Error reading keytab file", e);
            return null;
        }
    }

    @PostMapping("/nodes/heartbeat")
    public ResponseEntity<String> nodeHeartbeat(@RequestBody NodeHeartbeat heartbeat) {
        if (heartbeat.getNodeId() == null || heartbeat.getTimestamp() <= 0) {
            logger.error("Invalid heartbeat data: {}", heartbeat);
            return ResponseEntity.badRequest().body("Invalid heartbeat data");
        }
        if (nodeService.nodeIsNotRegistered(heartbeat.getNodeId())) {
            logger.warn("Node not found for heartbeat. Node ID: {}. Attempting to re-register.", heartbeat.getNodeId());
            return ResponseEntity.status(404).body("Node not found, please re-register");
        }
        nodeService.updateHeartbeat(heartbeat.getNodeId());
        logger.debug("Heartbeat received for node: {}", heartbeat.getNodeId());
        return ResponseEntity.ok("Heartbeat received");
    }

    @PostMapping("/nodes/deregister")
    public ResponseEntity<String> deregisterNode(@RequestBody NodeDTO node) {
        if (node == null || node.getNodeId() == null) {
            logger.error("Invalid node information for deregistration: {}", node);
            return ResponseEntity.badRequest().body("Invalid node information");
        }
        if (nodeService.nodeIsNotRegistered(node.getNodeId())) {
            logger.warn("Node not found for deregistration. Node ID: {}", node.getNodeId());
            return ResponseEntity.status(404).body("Node not found");
        }
        nodeService.deregisterNode(node.getNodeId());
        logger.info("Node deregistered: {} ({})", node.getName(), node.getNodeId());
        return ResponseEntity.ok("Node deregistered successfully");
    }
}
