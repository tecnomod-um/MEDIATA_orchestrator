package org.taniwha.service;

import org.springframework.stereotype.Service;
import org.taniwha.dto.ProjectDTO;
import org.taniwha.dto.SaveProjectRequestDTO;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeMetadata;
import org.taniwha.model.Project;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Project related functionality
@Service
public class ProjectService {

    private static final String STRATIF_PROJECT_NAME = "STRATIF-AI";
    private static final String STRATIF_PROJECT_IMAGE_URL = "/stratif.png";

    private final ProjectRepository projectRepository;
    private final NodeAccessService nodeAccessService;
    private final UserRepository userRepository;
    private final NodeRepository nodeRepository;
    private final NodeService nodeService;

    public ProjectService(ProjectRepository projectRepository, NodeAccessService nodeAccessService, UserRepository userRepository, NodeRepository nodeRepository, NodeService nodeService) {
        this.projectRepository = projectRepository;
        this.nodeAccessService = nodeAccessService;
        this.userRepository = userRepository;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
    }

    public List<ProjectDTO> listProjects() {
        List<Project> entities = projectRepository.findAll();
        List<ProjectDTO> out = new ArrayList<>();

        int membersCount = (int) userRepository.count();

        Instant lastAccessInstant = nodeService.getLastNodeListAccess();
        String lastAccess = (lastAccessInstant != null) ? lastAccessInstant.toString() : null;

        for (Project e : entities) {
            out.add(toDto(e, membersCount, lastAccess));
        }

        return out;
    }

    public ProjectDTO saveProject(SaveProjectRequestDTO req) {
        Project p = new Project();

        if (req.getId() != null && !req.getId().trim().isEmpty()) {
            p.setId(req.getId().trim());
        }

        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setBadge(req.getBadge());
        p.setNodeIds(normalizeNodeIds(req.getNodeIds()));

        // Persist image
        if (req.getImageBase64() != null && !req.getImageBase64().trim().isEmpty() && req.getImageContentType() != null && !req.getImageContentType().trim().isEmpty()) {

            String base64 = req.getImageBase64().trim();
            int comma = base64.indexOf(',');
            if (base64.startsWith("data:") && comma >= 0) {
                base64 = base64.substring(comma + 1).trim();
            }

            byte[] bytes = Base64.getDecoder().decode(base64);
            p.setImageBytes(bytes);
            p.setImageContentType(req.getImageContentType().trim());
        } else {
            p.setImageBytes(null);
            p.setImageContentType(null);
        }

        Project saved = projectRepository.save(p);
        Instant lastAccessInstant = nodeService.getLastNodeListAccess();
        return toDto(saved, (int) userRepository.count(), lastAccessInstant != null ? lastAccessInstant.toString() : null);
    }

    public List<String> getProjectNodeIds(String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return projectRepository.findById(projectId.trim())
                .map(Project::getNodeIds)
                .map(this::normalizeNodeIds)
                .orElse(Collections.emptyList());
    }

    private ProjectDTO toDto(Project project, int membersCount, String lastAccess) {
        ProjectDTO dto = new ProjectDTO();
        dto.setId(project.getId());
        dto.setName(project.getName());
        dto.setDescription(project.getDescription());
        dto.setBadge(project.getBadge());
        dto.setNodeIds(normalizeNodeIds(project.getNodeIds()));
        dto.setMembersCount(membersCount);
        dto.setLastAccess(lastAccess);

        List<NodeInfo> activeProjectNodes = getActiveProjectNodes(dto.getNodeIds());
        dto.setNodesCount(activeProjectNodes.size());
        dto.setDcatCount(computeDcatCount(activeProjectNodes));

        setImageInDTO(project, dto);
        return dto;
    }

    private List<NodeInfo> getActiveProjectNodes(List<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<NodeInfo> nodes = new ArrayList<>();
        nodeRepository.findAllById(nodeIds).forEach(nodes::add);
        return nodes.stream()
                .filter(this::isActive)
                .collect(Collectors.toList());
    }

    private List<String> normalizeNodeIds(List<String> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> normalized = nodeIds.stream()
                .filter(nodeId -> nodeId != null && !nodeId.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ArrayList<>(normalized);
    }

    private void setImageInDTO(Project project, ProjectDTO dto) {
        if (project.getImageBytes() != null && project.getImageBytes().length > 0 && project.getImageContentType() != null && !project.getImageContentType().trim().isEmpty()) {
            String b64 = Base64.getEncoder().encodeToString(project.getImageBytes());
            dto.setImageUrl("data:" + project.getImageContentType().trim() + ";base64," + b64);
        } else if (STRATIF_PROJECT_NAME.equals(project.getName())) {
            dto.setImageUrl(STRATIF_PROJECT_IMAGE_URL);
        } else {
            dto.setImageUrl(null);
        }
    }

    private int computeDcatCount(List<NodeInfo> nodes) {
        int total = 0;

        for (NodeInfo node : nodes) {
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            NodeMetadata md = nodeAccessService.getMetadata(node.getNodeId());
            if (md == null) {
                continue;
            }
            List<NodeMetadata.Dataset> datasets = md.getDataset();
            if (datasets != null) {
                total += datasets.size();
            }
        }

        return total;
    }

    private boolean isActive(NodeInfo nodeInfo) {
        return nodeInfo != null && (nodeInfo.getActive() == null || nodeInfo.getActive());
    }
}
