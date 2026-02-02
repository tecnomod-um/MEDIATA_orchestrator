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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for schema operations.
 * Tests schema creation, retrieval, and deletion operations across the system.
 */
@AutoConfigureMockMvc
public class SchemaOperationsIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private NodeInfo testNode;

    @BeforeEach
    void setUpSchema() {
        nodeRepository.deleteAll();

        // Create a test node for schema operations
        testNode = new NodeInfo();
        testNode.setIp("192.168.1.50");
        testNode.setName("Schema Test Node");
        testNode = nodeRepository.save(testNode);
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testCreateSchema() throws Exception {
        Map<String, Object> schemaData = new HashMap<>();
        schemaData.put("nodeIp", "192.168.1.50");
        schemaData.put("schemaName", "PatientSchema");
        schemaData.put("version", "1.0");

        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field1 = new HashMap<>();
        field1.put("name", "patientId");
        field1.put("type", "String");
        fields.add(field1);

        Map<String, String> field2 = new HashMap<>();
        field2.put("name", "diagnosis");
        field2.put("type", "String");
        fields.add(field2);

        schemaData.put("fields", fields);

        Map<String, Object> request = new HashMap<>();
        request.put("schema", schemaData);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testGetSchemas() throws Exception {
        // First create a schema
        Map<String, Object> schemaData = new HashMap<>();
        schemaData.put("nodeIp", "192.168.1.50");
        schemaData.put("schemaName", "TestSchema");

        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field = new HashMap<>();
        field.put("name", "testField");
        field.put("type", "String");
        fields.add(field);
        schemaData.put("fields", fields);

        Map<String, Object> request = new HashMap<>();
        request.put("schema", schemaData);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Then retrieve it
        mockMvc.perform(get("/nodes/schema"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testDeleteSchema() throws Exception {
        // Create schema to delete
        Map<String, Object> schemaData = new HashMap<>();
        schemaData.put("nodeIp", "192.168.1.50");
        schemaData.put("schemaName", "DeleteMe");

        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field = new HashMap<>();
        field.put("name", "field1");
        field.put("type", "String");
        fields.add(field);
        schemaData.put("fields", fields);

        Map<String, Object> request = new HashMap<>();
        request.put("schema", schemaData);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk());

        // Delete the schema
        mockMvc.perform(delete("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void testUnauthorizedAccessToSchemas() throws Exception {
        // No authentication - should be forbidden
        mockMvc.perform(get("/nodes/schema"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testSchemaLifecycleIntegration() throws Exception {
        // Complete lifecycle: Create -> Retrieve -> Delete

        // 1. Create schema
        Map<String, Object> schemaData = new HashMap<>();
        schemaData.put("nodeIp", "192.168.1.50");
        schemaData.put("schemaName", "LifecycleSchema");

        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field1 = new HashMap<>();
        field1.put("name", "name");
        field1.put("type", "String");
        fields.add(field1);

        Map<String, String> field2 = new HashMap<>();
        field2.put("name", "age");
        field2.put("type", "Integer");
        fields.add(field2);

        schemaData.put("fields", fields);

        Map<String, Object> request = new HashMap<>();
        request.put("schema", schemaData);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf()))
                .andExpect(status().isOk());

        // 2. Retrieve schema
        mockMvc.perform(get("/nodes/schema"))
                .andExpect(status().isOk());

        // 3. Delete schema
        mockMvc.perform(delete("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
