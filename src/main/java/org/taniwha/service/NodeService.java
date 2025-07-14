package org.taniwha.service;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeSummary;
import org.taniwha.repository.NodeRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// All node related operations go here
@Service
public class NodeService {

    private static final Logger logger = LoggerFactory.getLogger(NodeService.class);
    private final NodeRepository nodeRepository;
    private final Map<String, Instant> nodeHeartbeats = new ConcurrentHashMap<>();

    private final KerberosService kerberosService;

    private final PasswordEncoder passwordEncoder;

    @Value("${kerberos.realm}")
    private String realm;

    @Value("${overwrite.node:false}")
    private boolean overwriteNode;

    @Autowired
    public NodeService(NodeRepository nodeRepository, KerberosService kerberosService, PasswordEncoder passwordEncoder) {
        this.nodeRepository = nodeRepository;
        this.kerberosService = kerberosService;
        this.passwordEncoder = passwordEncoder;
    }

    public String registerNode(NodeInfo nodeInfo) {
        if (nodeRepository.existsByIp(nodeInfo.getIp())) {
            if (overwriteNode) {
                NodeInfo existingNode = nodeRepository.findByIp(nodeInfo.getIp());
                deregisterNode(existingNode.getNodeId());
            } else {
                logger.error("Node with the same IP and port is already registered");
                return null;
            }
        }

        nodeRepository.save(nodeInfo);
        Instant now = Instant.now();
        nodeHeartbeats.put(nodeInfo.getNodeId(), now);

        String rawPassword = RandomStringUtils.randomAlphanumeric(16);
        String encodedPassword = passwordEncoder.encode(rawPassword);
        nodeInfo.setPassword(encodedPassword);
        try {
            // Create a Kerberos principal for the node
            kerberosService.createPrincipal(kerberosService.getPrincipalName(nodeInfo.getIp(), realm), rawPassword);
            return kerberosService.createKeytab(kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
        } catch (KrbException e) {
            logger.error("Failed to register node {}: {}", nodeInfo.getNodeId(), e.getMessage());
            return null;
        }
    }

    public void updateHeartbeat(String nodeId) {
        updateHeartbeat(nodeId, Instant.now());
    }

    public void updateHeartbeat(String nodeId, Instant lastHeartbeat) {
        nodeHeartbeats.put(nodeId, lastHeartbeat);
    }

    public void deregisterNode(String nodeId) {
        NodeInfo nodeInfo = findNodeById(nodeId);
        if (nodeInfo != null) {
            nodeRepository.deleteById(nodeId);
            nodeHeartbeats.remove(nodeId);
            try {
                kerberosService.deletePrincipal(kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
            } catch (KrbException e) {
                logger.error("Failed to delete Kerberos principal and keytab for node");
            }
        }
    }

    public boolean nodeIsNotRegistered(String nodeId) {
        return !nodeRepository.existsById(nodeId);
    }

    public Iterable<NodeInfo> getActiveNodes() {
        return nodeRepository.findAll();
    }

    public Instant getLastHeartbeat(String nodeId) {
        return nodeHeartbeats.get(nodeId);
    }

    public NodeInfo findNodeById(String nodeId) {
        return nodeRepository.findById(nodeId).orElse(null);
    }

    public List<NodeSummary> getNodeSummaries() {
        return nodeRepository.findAll().stream()
                .map(node -> new NodeSummary(node.getNodeId(), node.getName(), node.getDescription(), node.getColor())).collect(Collectors.toList());
    }
}
