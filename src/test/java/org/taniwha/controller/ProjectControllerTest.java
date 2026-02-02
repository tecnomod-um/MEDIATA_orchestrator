package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.ProjectDTO;
import org.taniwha.dto.SaveProjectRequestDTO;
import org.taniwha.service.ProjectService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectControllerTest {

    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();
    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new ProjectController(projectService))
                .build();
    }

    @Test
    void listProjects_emptyList_returns200() throws Exception {
        when(projectService.listProjects()).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/projects/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(projectService).listProjects();
    }

    @Test
    void listProjects_withProjects_returns200() throws Exception {
        ProjectDTO dto1 = new ProjectDTO();
        dto1.setId("p1");
        dto1.setName("Project 1");
        dto1.setDescription("Description 1");
        dto1.setBadge("badge1");
        dto1.setMembersCount(5);
        dto1.setNodesCount(3);
        dto1.setDcatCount(10);

        ProjectDTO dto2 = new ProjectDTO();
        dto2.setId("p2");
        dto2.setName("Project 2");
        dto2.setDescription("Description 2");
        dto2.setBadge("badge2");
        dto2.setMembersCount(8);
        dto2.setNodesCount(4);
        dto2.setDcatCount(15);

        List<ProjectDTO> projects = Arrays.asList(dto1, dto2);
        when(projectService.listProjects()).thenReturn(projects);

        mvc.perform(get("/api/projects/list"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("p1"))
                .andExpect(jsonPath("$[0].name").value("Project 1"))
                .andExpect(jsonPath("$[0].description").value("Description 1"))
                .andExpect(jsonPath("$[0].badge").value("badge1"))
                .andExpect(jsonPath("$[0].membersCount").value(5))
                .andExpect(jsonPath("$[0].nodesCount").value(3))
                .andExpect(jsonPath("$[0].dcatCount").value(10))
                .andExpect(jsonPath("$[1].id").value("p2"))
                .andExpect(jsonPath("$[1].name").value("Project 2"));

        verify(projectService).listProjects();
    }

    @Test
    void saveProject_newProject_returns200() throws Exception {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setName("New Project");
        req.setDescription("New Description");
        req.setBadge("new-badge");

        ProjectDTO savedDto = new ProjectDTO();
        savedDto.setId("generated-id");
        savedDto.setName("New Project");
        savedDto.setDescription("New Description");
        savedDto.setBadge("new-badge");
        savedDto.setMembersCount(10);
        savedDto.setNodesCount(5);
        savedDto.setDcatCount(20);

        when(projectService.saveProject(any(SaveProjectRequestDTO.class))).thenReturn(savedDto);

        mvc.perform(post("/api/projects/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("generated-id"))
                .andExpect(jsonPath("$.name").value("New Project"))
                .andExpect(jsonPath("$.description").value("New Description"))
                .andExpect(jsonPath("$.badge").value("new-badge"))
                .andExpect(jsonPath("$.membersCount").value(10))
                .andExpect(jsonPath("$.nodesCount").value(5))
                .andExpect(jsonPath("$.dcatCount").value(20));

        verify(projectService).saveProject(any(SaveProjectRequestDTO.class));
    }

    @Test
    void saveProject_existingProject_returns200() throws Exception {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setId("existing-id");
        req.setName("Updated Project");
        req.setDescription("Updated Description");
        req.setBadge("updated-badge");

        ProjectDTO savedDto = new ProjectDTO();
        savedDto.setId("existing-id");
        savedDto.setName("Updated Project");
        savedDto.setDescription("Updated Description");
        savedDto.setBadge("updated-badge");
        savedDto.setMembersCount(12);
        savedDto.setNodesCount(6);
        savedDto.setDcatCount(25);

        when(projectService.saveProject(any(SaveProjectRequestDTO.class))).thenReturn(savedDto);

        mvc.perform(post("/api/projects/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("existing-id"))
                .andExpect(jsonPath("$.name").value("Updated Project"))
                .andExpect(jsonPath("$.description").value("Updated Description"));

        verify(projectService).saveProject(any(SaveProjectRequestDTO.class));
    }

    @Test
    void saveProject_withImage_returns200() throws Exception {
        SaveProjectRequestDTO req = new SaveProjectRequestDTO();
        req.setName("Project with Image");
        req.setDescription("Description");
        req.setBadge("badge");
        req.setImageBase64("data:image/png;base64,dGVzdA==");
        req.setImageContentType("image/png");

        ProjectDTO savedDto = new ProjectDTO();
        savedDto.setId("img-project");
        savedDto.setName("Project with Image");
        savedDto.setDescription("Description");
        savedDto.setBadge("badge");
        savedDto.setImageUrl("data:image/png;base64,dGVzdA==");
        savedDto.setMembersCount(0);
        savedDto.setNodesCount(0);
        savedDto.setDcatCount(0);

        when(projectService.saveProject(any(SaveProjectRequestDTO.class))).thenReturn(savedDto);

        mvc.perform(post("/api/projects/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("img-project"))
                .andExpect(jsonPath("$.imageUrl").value("data:image/png;base64,dGVzdA=="));

        verify(projectService).saveProject(any(SaveProjectRequestDTO.class));
    }
}
