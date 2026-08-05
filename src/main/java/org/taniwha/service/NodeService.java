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
import org.taniwha.model.Project;
import org.taniwha.model.User;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.UserRepository;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

// All node-related operations go here
@Service
public class NodeService {

    private static final Logger logger = LoggerFactory.getLogger(NodeService.class);
    private static final String DEFAULT_PROJECT_NAME = "Default Project";
    private static final String STRATIF_PROJECT_NAME = "STRATIF-AI";
    private static final String STRATIF_PROJECT_DESCRIPTION = "STRATIF-AI deployment workspace";
    private static final String STRATIF_PROJECT_BADGE = "Active";
    private static final String DEFAULT_LOCAL_NODE_NAME = "Default Local";
    private static final List<String> DEFAULT_LOCAL_NODE_HOSTS = List.of(
            "localhost",
            "127.0.0.1",
            "mediata-default-node.local"
    );
    private final NodeRepository nodeRepository;
    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final Map<String, Instant> nodeHeartbeats = new ConcurrentHashMap<>();

    private final KerberosService kerberosService;

    private final PasswordEncoder passwordEncoder;

    @Value("${kerberos.realm}")
    private String realm;

    @Value("${overwrite.node:false}")
    private boolean overwriteNode;

    @Value("${node.reclaim.window.minutes:60}")
    private long nodeReclaimWindowMinutes;

    @Getter
    private volatile Instant lastNodeListAccess;

    @Autowired
    public NodeService(NodeRepository nodeRepository, UserRepository userRepository, ProjectRepository projectRepository, KerberosService kerberosService, PasswordEncoder passwordEncoder) {
        this.nodeRepository = nodeRepository;
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.kerberosService = kerberosService;
        this.passwordEncoder = passwordEncoder;
    }

    public String registerNode(NodeInfo nodeInfo) {
        Instant now = Instant.now();
        NodeInfo existingNode = findNodeRegistrationByIp(nodeInfo.getIp());
        if (existingNode != null) {
            if (isActive(existingNode)) {
                if (overwriteNode) {
                    deleteRegisteredNode(existingNode);
                } else {
                    logger.error("Node with the same IP and port is already registered");
                    return null;
                }
            } else if (canReclaimNodeId(existingNode, nodeInfo, now)) {
                nodeInfo.setNodeId(existingNode.getNodeId());
                logger.info("Reclaiming node ID {} for restarted node {}", existingNode.getNodeId(), nodeInfo.getName());
            } else {
                nodeRepository.deleteById(existingNode.getNodeId());
            }
        }

        nodeInfo.setActive(true);
        nodeInfo.setDeregisteredAt(null);
        nodeRepository.save(nodeInfo);
        nodeHeartbeats.put(nodeInfo.getNodeId(), now);
        assignNodeToProject(nodeInfo);

        String rawPassword = RandomStringUtils.randomAlphanumeric(16);
        String encodedPassword = passwordEncoder.encode(rawPassword);
        nodeInfo.setPassword(encodedPassword);

        // Grant access to the default admin user for local/development deployments
        grantAdminAccessToNode(nodeInfo);

        try {
            kerberosService.createPrincipal(kerberosService.getPrincipalName(nodeInfo.getIp(), realm), rawPassword);
            return kerberosService.createKeytab(kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
        } catch (KrbException e) {
            logger.error("Failed to register node {}: {}", nodeInfo.getNodeId(), e.getMessage());
            return null;
        }
    }

    private void assignNodeToProject(NodeInfo nodeInfo) {
        if (nodeInfo == null || nodeInfo.getNodeId() == null || nodeInfo.getNodeId().trim().isEmpty()) {
            return;
        }
        if (isDefaultProjectNode(nodeInfo)) {
            assignNodeToDefaultProject(nodeInfo);
            removeNodeFromStratifProject(nodeInfo.getNodeId());
            return;
        }

        assignNodeToStratifProject(nodeInfo);
    }

    private void assignNodeToDefaultProject(NodeInfo nodeInfo) {
        try {
            Project project = projectRepository.findByName(DEFAULT_PROJECT_NAME);
            if (project == null) {
                project = new Project();
                project.setName(DEFAULT_PROJECT_NAME);
                project.setDescription("Default project created on initialization");
                project.setBadge("default");
            }
            addNodeToProject(project, nodeInfo.getNodeId());
            logger.info("Assigned node {} ({}) to project '{}'", nodeInfo.getName(), nodeInfo.getNodeId(), DEFAULT_PROJECT_NAME);
        } catch (Exception e) {
            logger.error("Failed to assign node {} to project '{}'", nodeInfo.getNodeId(), DEFAULT_PROJECT_NAME, e);
        }
    }

    private void assignNodeToStratifProject(NodeInfo nodeInfo) {
        try {
            Project project = projectRepository.findByName(STRATIF_PROJECT_NAME);
            if (project == null) {
                project = new Project();
                project.setName(STRATIF_PROJECT_NAME);
                project.setDescription(STRATIF_PROJECT_DESCRIPTION);
                project.setBadge(STRATIF_PROJECT_BADGE);
            }

            addNodeToProject(project, nodeInfo.getNodeId());
            logger.info("Assigned node {} ({}) to project '{}'", nodeInfo.getName(), nodeInfo.getNodeId(), STRATIF_PROJECT_NAME);
        } catch (Exception e) {
            logger.error("Failed to assign node {} to project '{}'", nodeInfo.getNodeId(), STRATIF_PROJECT_NAME, e);
        }
    }

    private void addNodeToProject(Project project, String nodeId) {
        List<String> nodeIds = project.getNodeIds();
        if (nodeIds == null) {
            nodeIds = new ArrayList<>();
        }

        boolean alreadyAssigned = nodeIds.stream()
                .filter(id -> id != null)
                .map(String::trim)
                .anyMatch(nodeId::equals);
        if (!alreadyAssigned) {
            nodeIds.add(nodeId);
            project.setNodeIds(nodeIds);
            projectRepository.save(project);
        }
    }

    private void removeNodeFromStratifProject(String nodeId) {
        try {
            Project project = projectRepository.findByName(STRATIF_PROJECT_NAME);
            if (project == null || project.getNodeIds() == null) {
                return;
            }
            List<String> nodeIds = project.getNodeIds().stream()
                    .filter(id -> id != null && !nodeId.equals(id.trim()))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (nodeIds.size() != project.getNodeIds().size()) {
                project.setNodeIds(nodeIds);
                projectRepository.save(project);
                logger.info("Removed node {} from project '{}'", nodeId, STRATIF_PROJECT_NAME);
            }
        } catch (Exception e) {
            logger.error("Failed to remove node {} from project '{}'", nodeId, STRATIF_PROJECT_NAME, e);
        }
    }

    private boolean isDefaultProjectNode(NodeInfo nodeInfo) {
        return isDefaultLocalNode(nodeInfo) || isAssignedToDefaultProject(nodeInfo.getNodeId());
    }

    private boolean isDefaultLocalNode(NodeInfo nodeInfo) {
        if (nodeInfo == null) {
            return false;
        }
        String name = nodeInfo.getName();
        if (name != null && DEFAULT_LOCAL_NODE_NAME.equalsIgnoreCase(name.trim())) {
            return true;
        }

        String serviceUrl = nodeInfo.getServiceUrl();
        if (serviceUrl == null || serviceUrl.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(serviceUrl.trim());
            String host = uri.getHost();
            return host != null && DEFAULT_LOCAL_NODE_HOSTS.stream()
                    .anyMatch(defaultHost -> defaultHost.equalsIgnoreCase(host));
        } catch (URISyntaxException e) {
            logger.debug("Could not parse node service URL '{}' while checking default project assignment", serviceUrl);
            return false;
        }
    }

    private boolean isAssignedToDefaultProject(String nodeId) {
        Project defaultProject = projectRepository.findByName(DEFAULT_PROJECT_NAME);
        if (defaultProject == null || defaultProject.getNodeIds() == null) {
            return false;
        }

        return defaultProject.getNodeIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(nodeId::equals);
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
        NodeInfo nodeInfo = nodeRepository.findById(nodeId).orElse(null);
        if (nodeInfo != null) {
            nodeInfo.setActive(false);
            nodeInfo.setDeregisteredAt(Instant.now());
            nodeRepository.save(nodeInfo);
            nodeHeartbeats.remove(nodeId);
            removeNodeFromProjects(nodeId);
            deleteKerberosPrincipal(nodeInfo);
        }
    }

    private void removeNodeFromProjects(String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            return;
        }

        String normalizedNodeId = nodeId.trim();
        for (Project project : projectRepository.findAll()) {
            if (project == null || project.getNodeIds() == null || project.getNodeIds().isEmpty()) {
                continue;
            }

            List<String> remainingNodeIds = project.getNodeIds().stream()
                    .filter(id -> id != null && !normalizedNodeId.equals(id.trim()))
                    .collect(Collectors.toCollection(ArrayList::new));

            if (remainingNodeIds.size() != project.getNodeIds().size()) {
                project.setNodeIds(remainingNodeIds);
                projectRepository.save(project);
                logger.info("Removed deregistered node {} from project '{}'", normalizedNodeId, project.getName());
            }
        }
    }

    public boolean nodeIsNotRegistered(String nodeId) {
        return nodeRepository.findById(nodeId)
                .map(nodeInfo -> !isActive(nodeInfo))
                .orElse(true);
    }

    public Iterable<NodeInfo> getActiveNodes() {
        return nodeRepository.findAll().stream()
                .filter(this::isActive)
                .collect(Collectors.toList());
    }

    public Instant getLastHeartbeat(String nodeId) {
        return nodeHeartbeats.get(nodeId);
    }

    public NodeInfo findNodeById(String nodeId) {
        return nodeRepository.findById(nodeId)
                .filter(this::isActive)
                .orElse(null);
    }

    public List<NodeSummary> getNodeSummaries() {
        lastNodeListAccess = Instant.now();
        return nodeRepository.findAll().stream()
                .filter(this::isActive)
                .map(node -> new NodeSummary(
                        node.getNodeId(),
                        node.getName(),
                        node.getDescription(),
                        node.getColor(),
                        node.getServiceUrl()))
                .collect(Collectors.toList());
    }

    private NodeInfo findNodeRegistrationByIp(String ip) {
        List<NodeInfo> candidates = nodeRepository.findAllByIp(ip);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        Optional<NodeInfo> activeNode = candidates.stream()
                .filter(this::isActive)
                .findFirst();
        if (activeNode.isPresent()) {
            return activeNode.get();
        }

        return candidates.stream()
                .max(Comparator.comparing(node -> Optional.ofNullable(node.getDeregisteredAt()).orElse(Instant.EPOCH)))
                .orElse(null);
    }

    private boolean canReclaimNodeId(NodeInfo existingNode, NodeInfo incomingNode, Instant now) {
        if (nodeReclaimWindowMinutes <= 0 || existingNode.getDeregisteredAt() == null) {
            return false;
        }

        Instant reclaimDeadline = existingNode.getDeregisteredAt().plus(Duration.ofMinutes(nodeReclaimWindowMinutes));
        return !reclaimDeadline.isBefore(now)
                && Objects.equals(existingNode.getIp(), incomingNode.getIp())
                && Objects.equals(existingNode.getName(), incomingNode.getName());
    }

    private boolean isActive(NodeInfo nodeInfo) {
        return nodeInfo != null && (nodeInfo.getActive() == null || nodeInfo.getActive());
    }

    private void deleteRegisteredNode(NodeInfo nodeInfo) {
        nodeRepository.deleteById(nodeInfo.getNodeId());
        nodeHeartbeats.remove(nodeInfo.getNodeId());
        deleteKerberosPrincipal(nodeInfo);
    }

    private void deleteKerberosPrincipal(NodeInfo nodeInfo) {
        try {
            kerberosService.deletePrincipal(kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
        } catch (KrbException e) {
            logger.error("Failed to delete Kerberos principal and keytab for node");
        }
    }
}
