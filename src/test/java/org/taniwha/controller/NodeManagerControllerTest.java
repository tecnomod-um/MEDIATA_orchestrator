package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.NodeDTO;
import org.taniwha.model.NodeHeartbeat;
import org.taniwha.service.NodeService;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NodeManagerControllerTest {

    private MockMvc mvc;
    private NodeService nodeService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() {
        nodeService = mock(NodeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NodeManagerController(nodeService)).build();
    }

    @Test
    void registerNode_invalidIp_returnsBadRequest() throws Exception {
        NodeDTO dto = new NodeDTO();
        mvc.perform(post("/nodes/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid node information"));
        verifyNoInteractions(nodeService);
    }

    @Test
    void registerNode_serviceThrowsConflict_returns409() throws Exception {
        NodeDTO dto = new NodeDTO();
        dto.setIp("1.2.3.4");
        dto.setNodeId("n1");
        dto.setName("N");
        dto.setPassword("pw");
        dto.setDescription("d");
        dto.setColor("c");
        dto.setPublicKey("pk");

        when(nodeService.registerNode(any())).thenThrow(new RuntimeException("boom"));

        mvc.perform(post("/nodes/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("boom"));

        verify(nodeService).registerNode(any());
    }

    @Test
    void registerNode_success_readsKeytabAndReturnsBase64() throws Exception {
        NodeDTO dto = new NodeDTO();
        dto.setIp("1.2.3.4");
        dto.setNodeId("n1");
        dto.setName("N");
        dto.setPassword("pw");
        dto.setDescription("d");
        dto.setColor("c");
        dto.setPublicKey("pk");

        File tmp = File.createTempFile("pref", ".keytab");
        tmp.deleteOnExit();
        byte[] bytes = "hello".getBytes();
        Files.write(tmp.toPath(), bytes);

        when(nodeService.registerNode(any())).thenReturn(tmp.getAbsolutePath());

        mvc.perform(post("/nodes/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Node registered successfully"))
                .andExpect(jsonPath("$.keytab")
                        .value(Base64.getEncoder().encodeToString(bytes)));

        verify(nodeService).registerNode(any());
    }

    @Test
    void nodeHeartbeat_invalidPayload_returnsBadRequest() throws Exception {
        NodeHeartbeat hb = new NodeHeartbeat();
        mvc.perform(post("/nodes/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(hb)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid heartbeat data"));
        verifyNoInteractions(nodeService);
    }

    @Test
    void nodeHeartbeat_notRegistered_returns404() throws Exception {
        NodeHeartbeat hb = new NodeHeartbeat();
        hb.setNodeId("n1");
        hb.setTimestamp(123L);

        when(nodeService.nodeIsNotRegistered("n1")).thenReturn(true);
        mvc.perform(post("/nodes/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(hb)))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Node not found, please re-register"));

        verify(nodeService).nodeIsNotRegistered("n1");
        verify(nodeService, never()).updateHeartbeat(any());
    }

    @Test
    void nodeHeartbeat_registered_updatesAndReturnsOk() throws Exception {
        NodeHeartbeat hb = new NodeHeartbeat();
        hb.setNodeId("n1");
        hb.setTimestamp(123L);

        when(nodeService.nodeIsNotRegistered("n1")).thenReturn(false);
        mvc.perform(post("/nodes/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(hb)))
                .andExpect(status().isOk())
                .andExpect(content().string("Heartbeat received"));

        verify(nodeService).updateHeartbeat("n1");
    }

    @Test
    void deregisterNode_invalidPayload_returnsBadRequest() throws Exception {
        NodeDTO dto = new NodeDTO();
        mvc.perform(post("/nodes/deregister")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid node information"));
        verifyNoInteractions(nodeService);
    }

    @Test
    void deregisterNode_notRegistered_returns404() throws Exception {
        NodeDTO dto = new NodeDTO();
        dto.setNodeId("n1");
        dto.setName("Name");

        when(nodeService.nodeIsNotRegistered("n1")).thenReturn(true);
        mvc.perform(post("/nodes/deregister")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Node not found"));
        verify(nodeService).nodeIsNotRegistered("n1");
        verify(nodeService, never()).deregisterNode(any());
    }

    @Test
    void deregisterNode_success_callsServiceAndReturnsOk() throws Exception {
        NodeDTO dto = new NodeDTO();
        dto.setNodeId("n1");
        dto.setName("Name");
        when(nodeService.nodeIsNotRegistered("n1")).thenReturn(false);
        mvc.perform(post("/nodes/deregister")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("Node deregistered successfully"));
        verify(nodeService).deregisterNode("n1");
    }
}
