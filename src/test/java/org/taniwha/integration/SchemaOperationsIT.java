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

import java.util.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

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
        Map<String, Object> schemaRequest = new HashMap<>();
        schemaRequest.put("nodeIp", "192.168.1.50");
        schemaRequest.put("schemaName", "PatientSchema");
        schemaRequest.put("version", "1.0");
        
        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field1 = new HashMap<>();
        field1.put("name", "patientId");
        field1.put("type", "String");
        fields.add(field1);
        
        Map<String, String> field2 = new HashMap<>();
        field2.put("name", "diagnosis");
        field2.put("type", "String");
        fields.add(field2);
        
        schemaRequest.put("fields", fields);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(schemaRequest)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testGetSchemas() throws Exception {
        // First create a schema
        Map<String, Object> schemaRequest = new HashMap<>();
        schemaRequest.put("nodeIp", "192.168.1.50");
        schemaRequest.put("schemaName", "TestSchema");
        
        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field = new HashMap<>();
        field.put("name", "testField");
        field.put("type", "String");
        fields.add(field);
        schemaRequest.put("fields", fields);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(schemaRequest)))
                .andExpect(status().isOk());

        // Then retrieve it
        mockMvc.perform(get("/nodes/schema"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testDeleteSchema() throws Exception {
        // Create schema to delete
        Map<String, Object> schemaRequest = new HashMap<>();
        schemaRequest.put("nodeIp", "192.168.1.50");
        schemaRequest.put("schemaName", "DeleteMe");
        
        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field = new HashMap<>();
        field.put("name", "field1");
        field.put("type", "String");
        fields.add(field);
        schemaRequest.put("fields", fields);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(schemaRequest)))
                .andExpect(status().isOk());

        // Delete the schema
        Map<String, String> deleteRequest = new HashMap<>();
        deleteRequest.put("schemaName", "DeleteMe");
        
        mockMvc.perform(delete("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
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
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("nodeIp", "192.168.1.50");
        createRequest.put("schemaName", "LifecycleSchema");
        
        List<Map<String, String>> fields = new ArrayList<>();
        Map<String, String> field1 = new HashMap<>();
        field1.put("name", "name");
        field1.put("type", "String");
        fields.add(field1);
        
        Map<String, String> field2 = new HashMap<>();
        field2.put("name", "age");
        field2.put("type", "Integer");
        fields.add(field2);
        
        createRequest.put("fields", fields);

        mockMvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk());

        // 2. Retrieve schema
        mockMvc.perform(get("/nodes/schema"))
                .andExpect(status().isOk());

        // 3. Delete schema
        Map<String, String> deleteRequest = new HashMap<>();
        deleteRequest.put("schemaName", "LifecycleSchema");
        
        mockMvc.perform(delete("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(deleteRequest)))
                .andExpect(status().isOk());
    }
}
