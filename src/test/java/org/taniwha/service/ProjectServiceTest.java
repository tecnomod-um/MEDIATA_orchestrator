package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.taniwha.dto.ProjectDTO;
import org.taniwha.dto.SaveProjectRequestDTO;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeMetadata;
import org.taniwha.model.Project;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.UserRepository;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProjectServiceTest {

    @Mock
    private ProjectRepository projectRepo;
    @Mock
    private NodeAccessService nodeAccessService;
    @Mock
    private UserRepository userRepo;
    @Mock
    private NodeRepository nodeRepo;
    @Mock
    private NodeService nodeService;

    private ProjectService svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new ProjectService(projectRepo, nodeAccessService, userRepo, nodeRepo, nodeService);
    }

    @Test
    void listProjects_emptyList_returnsEmptyList() {
        when(projectRepo.findAll()).thenReturn(Collections.emptyList());
        when(userRepo.count()).thenReturn(0L);
        when(nodeRepo.count()).thenReturn(0L);
        when(nodeService.getLastNodeListAccess()).thenReturn(null);

        List<ProjectDTO> result = svc.listProjects();

        assertThat(result).isEmpty();
        verify(projectRepo).findAll();
    }

    @Test
    void listProjects_withProjects_returnsProjectDTOs() {
        Project p1 = new Project();
        p1.setId("p1");
        p1.setName("Project 1");
        p1.setDescription("Description 1");
        p1.setBadge("badge1");

        Project p2 = new Project();
        p2.setId("p2");
        p2.setName("Project 2");
        p2.setDescription("Description 2");
        p2.setBadge("badge2");
        p2.setImageBytes("test-image".getBytes());
        p2.setImageContentType("image/png");

        when(projectRepo.findAll()).thenReturn(Arrays.asList(p1, p2));
        when(userRepo.count()).thenReturn(5L);
        when(nodeRepo.count()).thenReturn(3L);
        Instant now = Instant.now();
        when(nodeService.getLastNodeListAccess()).thenReturn(now);
        when(nodeRepo.findAll()).thenReturn(Collections.emptyList());

        List<ProjectDTO> result = svc.listProjects();

        assertThat(result).hasSize(2);
        
        ProjectDTO dto1 = result.get(0);
        assertThat(dto1.getId()).isEqualTo("p1");
        assertThat(dto1.getName()).isEqualTo("Project 1");
        assertThat(dto1.getDescription()).isEqualTo("Description 1");
        assertThat(dto1.getBadge()).isEqualTo("badge1");
        assertThat(dto1.getMembersCount()).isEqualTo(5);
        assertThat(dto1.getNodesCount()).isEqualTo(3);
        assertThat(dto1.getLastAccess()).isEqualTo(now.toString());
        assertThat(dto1.getImageUrl()).isNull();

        ProjectDTO dto2 = result.get(1);
        assertThat(dto2.getId()).isEqualTo("p2");
        assertThat(dto2.getImageUrl()).startsWith("data:image/png;base64,");
    }

    @Test
    void listProjects_computesDcatCount() {
        when(projectRepo.findAll()).thenReturn(Collections.emptyList());
        when(userRepo.count()).thenReturn(0L);
        when(nodeRepo.count()).thenReturn(1L);
        when(nodeService.getLastNodeListAccess()).thenReturn(null);

        NodeInfo node = new NodeInfo();
        node.setNodeId("n1");
        when(nodeRepo.findAll()).thenReturn(Collections.singletonList(node));

        NodeMetadata metadata = new NodeMetadata();
        NodeMetadata.Dataset ds1 = new NodeMetadata.Dataset();
        ds1.setTitle("Dataset 1");
        NodeMetadata.Dataset ds2 = new NodeMetadata.Dataset();
        ds2.setTitle("Dataset 2");
        metadata.setDataset(Arrays.asList(ds1, ds2));
        when(nodeAccessService.getMetadata("n1")).thenReturn(metadata);

        List<ProjectDTO> result = svc.listProjects();

        // Even though no projects, the dcat count should be computed
        verify(nodeAccessService).getMetadata("n1");
    }

    @Test
    void saveProject_newProject_savesAndReturnsDTO() {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setName("New Project");
        req.setDescription("New Description");
        req.setBadge("new-badge");

        Project saved = new Project();
        saved.setId("generated-id");
        saved.setName("New Project");
        saved.setDescription("New Description");
        saved.setBadge("new-badge");

        when(projectRepo.save(any(Project.class))).thenReturn(saved);
        when(userRepo.count()).thenReturn(10L);
        when(nodeRepo.count()).thenReturn(5L);
        when(nodeService.getLastNodeListAccess()).thenReturn(null);
        when(nodeRepo.findAll()).thenReturn(Collections.emptyList());

        ProjectDTO result = svc.saveProject(req);

        assertThat(result.getId()).isEqualTo("generated-id");
        assertThat(result.getName()).isEqualTo("New Project");
        assertThat(result.getDescription()).isEqualTo("New Description");
        assertThat(result.getBadge()).isEqualTo("new-badge");
        assertThat(result.getMembersCount()).isEqualTo(10);
        assertThat(result.getNodesCount()).isEqualTo(5);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepo).save(captor.capture());
        Project captured = captor.getValue();
        assertThat(captured.getName()).isEqualTo("New Project");
        assertThat(captured.getId()).isNull(); // ID should be null for new projects
    }

    @Test
    void saveProject_existingProject_updatesProject() {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setId("existing-id");
        req.setName("Updated Project");
        req.setDescription("Updated Description");
        req.setBadge("updated-badge");

        Project saved = new Project();
        saved.setId("existing-id");
        saved.setName("Updated Project");
        saved.setDescription("Updated Description");
        saved.setBadge("updated-badge");

        when(projectRepo.save(any(Project.class))).thenReturn(saved);
        when(userRepo.count()).thenReturn(10L);
        when(nodeRepo.count()).thenReturn(5L);
        when(nodeService.getLastNodeListAccess()).thenReturn(null);
        when(nodeRepo.findAll()).thenReturn(Collections.emptyList());

        ProjectDTO result = svc.saveProject(req);

        assertThat(result.getId()).isEqualTo("existing-id");
        assertThat(result.getName()).isEqualTo("Updated Project");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepo).save(captor.capture());
        Project captured = captor.getValue();
        assertThat(captured.getId()).isEqualTo("existing-id");
    }

    @Test
    void saveProject_withImage_savesImageData() {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setName("Project with Image");
        req.setDescription("Description");
        req.setBadge("badge");
        // Base64 encoded "test" string with data URI prefix
        req.setImageBase64("data:image/png;base64,dGVzdA==");
        req.setImageContentType("image/png");

        Project saved = new Project();
        saved.setId("img-project");
        saved.setName("Project with Image");
        saved.setDescription("Description");
        saved.setBadge("badge");
        saved.setImageBytes(Base64.getDecoder().decode("dGVzdA=="));
        saved.setImageContentType("image/png");

        when(projectRepo.save(any(Project.class))).thenReturn(saved);
        when(userRepo.count()).thenReturn(0L);
        when(nodeRepo.count()).thenReturn(0L);
        when(nodeService.getLastNodeListAccess()).thenReturn(null);
        when(nodeRepo.findAll()).thenReturn(Collections.emptyList());

        ProjectDTO result = svc.saveProject(req);

        assertThat(result.getId()).isEqualTo("img-project");
        assertThat(result.getImageUrl()).startsWith("data:image/png;base64,");
        assertThat(result.getImageUrl()).contains("dGVzdA==");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepo).save(captor.capture());
        Project captured = captor.getValue();
        assertThat(captured.getImageBytes()).isNotNull();
        assertThat(captured.getImageContentType()).isEqualTo("image/png");
    }

    @Test
    void saveProject_withoutImage_savesNullImage() {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setName("Project without Image");
        req.setDescription("Description");
        req.setBadge("badge");

        Project saved = new Project();
        saved.setId("no-img-project");
        saved.setName("Project without Image");
        saved.setDescription("Description");
        saved.setBadge("badge");

        when(projectRepo.save(any(Project.class))).thenReturn(saved);
        when(userRepo.count()).thenReturn(0L);
        when(nodeRepo.count()).thenReturn(0L);
        when(nodeService.getLastNodeListAccess()).thenReturn(null);
        when(nodeRepo.findAll()).thenReturn(Collections.emptyList());

        ProjectDTO result = svc.saveProject(req);

        assertThat(result.getImageUrl()).isNull();

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepo).save(captor.capture());
        Project captured = captor.getValue();
        assertThat(captured.getImageBytes()).isNull();
        assertThat(captured.getImageContentType()).isNull();
    }
}
