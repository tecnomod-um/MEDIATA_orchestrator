package org.taniwha.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.taniwha.model.NodeInfo;
import org.taniwha.repository.NodeRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for node management operations.
 * Tests the complete CRUD lifecycle of nodes including creation, retrieval, updates, and deletion.
 */
@AutoConfigureMockMvc
public class NodeManagementIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpNodes() {
        nodeRepository.deleteAll();
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testRegisterNode() throws Exception {
        Map<String, Object> nodeRequest = new HashMap<>();
        nodeRequest.put("ip", "192.168.1.100");
        nodeRequest.put("name", "Test Node 1");
        nodeRequest.put("port", 8088);
        nodeRequest.put("description", "A test node for integration testing");

        mockMvc.perform(post("/nodes/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nodeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("registered successfully")));

        // Verify node was saved to database
        List<NodeInfo> nodes = nodeRepository.findAll();
        assertEquals(1, nodes.size());
        assertEquals("192.168.1.100", nodes.get(0).getIp());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testNodeHeartbeat() throws Exception {
        // First register a node
        NodeInfo node = new NodeInfo();
        node.setIp("192.168.1.101");
        node.setName("Heartbeat Test Node");
        node = nodeRepository.save(node);

        Map<String, Object> heartbeatRequest = new HashMap<>();
        heartbeatRequest.put("nodeId", node.getNodeId());
        heartbeatRequest.put("timestamp", System.currentTimeMillis());

        mockMvc.perform(post("/nodes/heartbeat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testNodeDeregistration() throws Exception {
        // First register a node
        NodeInfo node = new NodeInfo();
        node.setIp("192.168.1.102");
        node.setName("Deregister Test Node");
        node = nodeRepository.save(node);

        Map<String, Object> deregisterRequest = new HashMap<>();
        deregisterRequest.put("nodeId", node.getNodeId());

        mockMvc.perform(post("/nodes/deregister")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deregisterRequest)))
                .andExpect(status().isOk());

        // Verify node was removed
        assertFalse(nodeRepository.existsById(node.getNodeId()));
    }

    @Test
    void testUnauthorizedAccessToNodes() throws Exception {
        // No authentication - should be forbidden when accessing protected endpoints
        Map<String, Object> deregisterRequest = new HashMap<>();
        deregisterRequest.put("ip", "192.168.1.1");

        mockMvc.perform(post("/nodes/deregister")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deregisterRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testNodeLifecycleIntegration() throws Exception {
        // Complete lifecycle: Register -> Heartbeat -> Deregister

        // 1. Register
        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("ip", "192.168.1.200");
        registerRequest.put("name", "Lifecycle Node");
        registerRequest.put("port", 8088);

        mockMvc.perform(post("/nodes/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isOk());

        // Verify registration
        NodeInfo registeredNode = nodeRepository.findByIp("192.168.1.200");
        assertNotNull(registeredNode);
        assertEquals("Lifecycle Node", registeredNode.getName());

        // 2. Heartbeat
        Map<String, Object> heartbeatRequest = new HashMap<>();
        heartbeatRequest.put("nodeId", registeredNode.getNodeId());
        heartbeatRequest.put("timestamp", System.currentTimeMillis());

        mockMvc.perform(post("/nodes/heartbeat")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(heartbeatRequest)))
                .andExpect(status().isOk());

        // 3. Deregister
        Map<String, Object> deregisterRequest = new HashMap<>();
        deregisterRequest.put("nodeId", registeredNode.getNodeId());

        mockMvc.perform(post("/nodes/deregister")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deregisterRequest)))
                .andExpect(status().isOk());

        // Verify deregistration
        assertFalse(nodeRepository.existsById(registeredNode.getNodeId()));
    }
}
