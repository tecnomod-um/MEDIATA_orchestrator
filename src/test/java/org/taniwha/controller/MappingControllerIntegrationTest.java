package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.MappingSuggestResponseDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.service.EmbeddingsClient;
import org.taniwha.service.MappingService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test that tests the actual MappingController with real LLM embeddings.
 * This test uses the exact curl request data provided by the user.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {MappingControllerIntegrationTest.TestConfig.class, MappingController.class},
    properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration"})
@AutoConfigureMockMvc(addFilters = false)
public class MappingControllerIntegrationTest {

    @Configuration
    static class TestConfig {
        @Bean
        public EmbeddingModel embeddingModel() {
            return new TransformersEmbeddingModel();
        }

        @Bean
        public EmbeddingsClient embeddingsClient(EmbeddingModel embeddingModel) {
            return new EmbeddingsClient(embeddingModel);
        }

        @Bean
        public MappingService mappingService(EmbeddingsClient embeddingsClient) {
            return new MappingService(embeddingsClient);
        }
        
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testActualCurlRequest() throws Exception {
        System.out.println("\n=== Testing Actual Curl Request Through Controller ===\n");
        
        // Load the exact curl request data from the user
        String requestJson = new String(Files.readAllBytes(Paths.get("/tmp/user_curl_request.json")));
        
        // Send POST request to the controller
        MvcResult result = mockMvc.perform(post("/api/mappings/suggest")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andReturn();
        
        // Parse response
        String responseJson = result.getResponse().getContentAsString();
        MappingSuggestResponseDTO response = objectMapper.readValue(responseJson, MappingSuggestResponseDTO.class);
        
        // Print results
        System.out.println("Success: " + response.isSuccess());
        System.out.println("Message: " + response.getMessage());
        System.out.println("Total mappings: " + response.getHierarchy().size());
        
        // Print mapping keys
        System.out.print("Mapping keys: [");
        for (int i = 0; i < response.getHierarchy().size(); i++) {
            Map<String, SuggestedMappingDTO> mapping = response.getHierarchy().get(i);
            for (String key : mapping.keySet()) {
                System.out.print(key);
                if (i < response.getHierarchy().size() - 1) System.out.print(", ");
            }
        }
        System.out.println("]");
        
        // Assertions
        assertTrue(response.isSuccess(), "Response should be successful");
        assertNotNull(response.getHierarchy(), "Hierarchy should not be null");
        assertTrue(response.getHierarchy().size() >= 25, 
            "Should have at least 25 mappings (got " + response.getHierarchy().size() + ")");
        
        // Check that we have proper mapping keys, not just "af"
        boolean hasSex = false;
        boolean hasBath = false;
        boolean hasAf = false;
        
        for (Map<String, SuggestedMappingDTO> mapping : response.getHierarchy()) {
            for (String key : mapping.keySet()) {
                if (key.contains("sex")) hasSex = true;
                if (key.contains("bath")) hasBath = true;
                if (key.equals("af")) hasAf = true;
            }
        }
        
        assertTrue(hasSex, "Should have 'sex' mapping");
        assertTrue(hasBath, "Should have 'bath' mapping");
        assertFalse(hasAf, "Should NOT have standalone 'af' mapping (it should be 'af_y')");
        
        System.out.println("\n✅ Controller integration test PASSED!");
        System.out.println("   - " + response.getHierarchy().size() + " mappings created");
        System.out.println("   - Proper semantic grouping (sex, bath, etc.)");
        System.out.println("   - No incorrect 'af' grouping\n");
    }
}
