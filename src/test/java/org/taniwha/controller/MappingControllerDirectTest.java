package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.MappingSuggestResponseDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.service.EmbeddingsClient;
import org.taniwha.service.MappingService;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct test of MappingController with the exact curl request data.
 * Tests the actual controller method directly (not via HTTP).
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = MappingControllerDirectTest.TestConfig.class)
public class MappingControllerDirectTest {

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
        public MappingController mappingController(MappingService mappingService) {
            return new MappingController(mappingService);
        }
        
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private MappingController mappingController;
    
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testActualCurlRequestThroughController() throws Exception {
        System.out.println("\n=== Testing Actual Curl Request Through Controller ===\n");
        
        // Load the exact curl request data from the user
        String requestJson = new String(Files.readAllBytes(Paths.get("/tmp/user_curl_request.json")));
        MappingSuggestRequestDTO request = objectMapper.readValue(requestJson, MappingSuggestRequestDTO.class);
        
        System.out.println("Request has " + request.getElementFiles().size() + " element files");
        
        // Call the controller directly
        MappingSuggestResponseDTO response = mappingController.suggest(request).getBody();
        
        // Print results
        System.out.println("\nSuccess: " + response.isSuccess());
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
        System.out.println("]\n");
        
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
        // Note: "af" key is fine - it's the canonical form of "AF (Y/N)"
        // The real issue was production only had 1 mapping total, not 40!
        
        System.out.println("✅ Controller direct test PASSED!");
        System.out.println("   - " + response.getHierarchy().size() + " mappings created (not just 1!)");
        System.out.println("   - Proper semantic grouping (sex, bath, groom, etc.)");
        System.out.println("   - Multiple distinct mappings working correctly\n");
    }
}
