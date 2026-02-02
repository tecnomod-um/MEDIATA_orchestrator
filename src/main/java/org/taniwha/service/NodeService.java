package org.taniwha.service;

import lombok.Getter;
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
import org.taniwha.model.User;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// All node related operations go here
@Service
public class NodeService {

    private static final Logger logger = LoggerFactory.getLogger(NodeService.class);
    private final NodeRepository nodeRepository;
    private final UserRepository userRepository;
    private final Map<String, Instant> nodeHeartbeats = new ConcurrentHashMap<>();

    private final KerberosService kerberosService;

    private final PasswordEncoder passwordEncoder;

    @Value("${kerberos.realm}")
    private String realm;

    @Value("${overwrite.node:false}")
    private boolean overwriteNode;

    @Getter
    private volatile Instant lastNodeListAccess;

    @Autowired
    public NodeService(NodeRepository nodeRepository, UserRepository userRepository, KerberosService kerberosService, PasswordEncoder passwordEncoder) {
        this.nodeRepository = nodeRepository;
        this.userRepository = userRepository;
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

        // Grant access to the default admin user for local/development deployments
        grantAdminAccessToNode(nodeInfo);

        try {
            // Create a Kerberos principal for the node
            kerberosService.createPrincipal(kerberosService.getPrincipalName(nodeInfo.getIp(), realm), rawPassword);
            return kerberosService.createKeytab(kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
        } catch (KrbException e) {
            logger.error("Failed to register node {}: {}", nodeInfo.getNodeId(), e.getMessage());
            return null;
        }
    }

    private void grantAdminAccessToNode(NodeInfo nodeInfo) {
        try {
            User adminUser = userRepository.findByUsername("admin");
            if (adminUser != null) {
                List<NodeInfo> nodeAccess = adminUser.getNodeIds();
                if (nodeAccess == null) {
                    nodeAccess = new ArrayList<>();
                }

                boolean alreadyHasAccess = nodeAccess.stream().filter(node -> node != null && node.getNodeId() != null).anyMatch(node -> node.getNodeId().equals(nodeInfo.getNodeId()));

                if (!alreadyHasAccess) {
                    nodeAccess.add(nodeInfo);
                    adminUser.setNodeIds(nodeAccess);
                    userRepository.save(adminUser);
                    logger.info("Granted admin user access to node: {} ({})", nodeInfo.getName(), nodeInfo.getNodeId());
                    logger.warn("SECURITY: Default admin user has access to this node. " + "Change admin password or revoke access in production environments!");
                }
            } else {
                logger.debug("Admin user not found, skipping automatic node access grant");
            }
        } catch (Exception e) {
            // Don't fail node registration if we can't grant admin access
            logger.error("Failed to grant default admin access to node {}", nodeInfo.getNodeId(), e);
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
        lastNodeListAccess = Instant.now();
        return nodeRepository.findAll().stream().map(node -> new NodeSummary(node.getNodeId(), node.getName(), node.getDescription(), node.getColor())).collect(Collectors.toList());
    }
}
