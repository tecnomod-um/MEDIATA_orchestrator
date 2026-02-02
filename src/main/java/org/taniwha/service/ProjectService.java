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
import java.util.List;

// Project related functionality
@Service
public class ProjectService {

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
        int nodesCount = (int) nodeRepository.count();

        Instant lastAccessInstant = nodeService.getLastNodeListAccess();
        String lastAccess = (lastAccessInstant != null) ? lastAccessInstant.toString() : null;
        int dcatCount = computeDcatCountAllNodes();

        for (Project e : entities) {
            ProjectDTO dto = new ProjectDTO();
            dto.setId(e.getId());
            dto.setName(e.getName());
            dto.setDescription(e.getDescription());
            dto.setBadge(e.getBadge());

            dto.setMembersCount(membersCount);
            dto.setNodesCount(nodesCount);
            dto.setDcatCount(dcatCount);
            dto.setLastAccess(lastAccess);

            setImageInDTO(e, dto);
            out.add(dto);
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
        ProjectDTO dto = new ProjectDTO();
        dto.setId(saved.getId());
        dto.setName(saved.getName());
        dto.setDescription(saved.getDescription());
        dto.setBadge(saved.getBadge());

        // computed fields
        dto.setMembersCount((int) userRepository.count());
        dto.setNodesCount((int) nodeRepository.count());
        dto.setDcatCount(computeDcatCountAllNodes());

        Instant lastAccessInstant = nodeService.getLastNodeListAccess();
        dto.setLastAccess(lastAccessInstant != null ? lastAccessInstant.toString() : null);

        setImageInDTO(saved, dto);
        return dto;
    }

    private void setImageInDTO(Project saved, ProjectDTO dto) {
        if (saved.getImageBytes() != null && saved.getImageBytes().length > 0 && saved.getImageContentType() != null && !saved.getImageContentType().trim().isEmpty()) {
            String b64 = Base64.getEncoder().encodeToString(saved.getImageBytes());
            dto.setImageUrl("data:" + saved.getImageContentType().trim() + ";base64," + b64);
        } else {
            dto.setImageUrl(null);
        }
    }

    private int computeDcatCountAllNodes() {
        int total = 0;

        List<NodeInfo> nodes = nodeRepository.findAll();
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
}
