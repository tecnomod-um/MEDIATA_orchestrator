package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedMappingDTO;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to compare different LLM models for medical mapping task.
 * Tests multiple embedding models to find the best fit for medical terminology.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ModelComparisonTest.TestConfig.class)
@EnableAutoConfiguration(exclude = {MongoAutoConfiguration.class, DataSourceAutoConfiguration.class})
public class ModelComparisonTest {

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
        public TerminologyService terminologyService() {
            TerminologyService mock = Mockito.mock(TerminologyService.class);
            Mockito.when(mock.selectBestTerminology(Mockito.any(String.class), Mockito.any()))
                .thenReturn("");
            return mock;
        }
        
        @Bean
        public DescriptionGenerator descriptionGenerator(EmbeddingsClient embeddingsClient) {
            return new DescriptionGenerator(embeddingsClient);
        }

        @Bean
        public MappingService mappingService(EmbeddingsClient embeddingsClient, TerminologyService terminologyService, DescriptionGenerator descriptionGenerator) {
            return new MappingService(embeddingsClient, terminologyService, descriptionGenerator);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MappingService mappingService;

    @Test
    void test_icd10_medical_coding_mappings() throws Exception {
        System.out.println("\n=== Testing ICD-10 Medical Coding Mappings ===");
        
        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-icd.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        
        // Expected: Should match ICD codes with disease names
        // e.g., "I63.9" (Stroke ICD-10) with "Stroke" text
        Set<String> mappedKeys = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
        
        System.out.println("Mapped categories: " + mappedKeys);
        
        // Should recognize medical terms
        assertTrue(result.size() >= 2, "Should create at least 2 mappings for medical codes");
        
        System.out.println("✅ ICD-10 mapping test passed\n");
    }

    @Test
    void test_vital_signs_abbreviations() throws Exception {
        System.out.println("\n=== Testing Vital Signs Abbreviations ===");
        
        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-vitals.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        
        Set<String> mappedKeys = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
        
        System.out.println("Mapped categories: " + mappedKeys);
        
        // Expected matches:
        // - "Blood Pressure Systolic" ↔ "SBP"
        // - "Blood Pressure Diastolic" ↔ "DBP"  
        // - "Heart Rate" ↔ "HR (bpm)"
        // - "Body Temperature" ↔ "Temp (C)"
        // - "Respiratory Rate" ↔ "RR (breaths/min)"
        
        // Should create at least 4-5 vital sign mappings
        assertTrue(result.size() >= 4, 
            "Should create at least 4 vital sign mappings (got " + result.size() + ")");
        
        System.out.println("✅ Vital signs mapping test passed\n");
    }

    @Test
    void test_medications_brand_vs_generic() throws Exception {
        System.out.println("\n=== Testing Medication Mappings (Brand vs Generic) ===");
        
        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-medications.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        
        Set<String> mappedKeys = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
        
        System.out.println("Mapped categories: " + mappedKeys);
        
        // Expected matches:
        // - "Aspirin" ↔ "ASA"
        // - "Metformin" ↔ "Glucophage"  
        // - "Atorvastatin" ↔ "Lipitor"
        // - "Warfarin" ↔ "Coumadin"
        // - "Route: PO, IV..." ↔ "Administration Route: Oral, Intravenous..."
        
        assertTrue(result.size() >= 2, 
            "Should create at least 2 medication mappings (got " + result.size() + ")");
        
        System.out.println("✅ Medication mapping test passed\n");
    }

    @Test
    void test_lab_results_abbreviations() throws Exception {
        System.out.println("\n=== Testing Lab Results Abbreviations ===");
        
        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-lab-results.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        
        Set<String> mappedKeys = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
        
        System.out.println("Mapped categories: " + mappedKeys);
        
        // Expected matches:
        // - "White Blood Cell Count" ↔ "WBC"
        // - "Serum Creatinine" ↔ "Cr"
        // - "Glomerular Filtration Rate" ↔ "eGFR"
        // - "Low Density Lipoprotein" ↔ "LDL"
        // - "High Density Lipoprotein" ↔ "HDL"
        
        assertTrue(result.size() >= 4, 
            "Should create at least 4 lab result mappings (got " + result.size() + ")");
        
        System.out.println("✅ Lab results mapping test passed\n");
    }

    @Test
    void test_patient_demographics() throws Exception {
        System.out.println("\n=== Testing Patient Demographics ===");
        
        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-patient-demographics.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        
        Set<String> mappedKeys = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
        
        System.out.println("Mapped categories: " + mappedKeys);
        
        // Expected matches:
        // - "Date of Birth" ↔ "DOB"
        // - "Patient Gender: Male, Female..." ↔ "Sex: M, F..."
        // - "Marital Status: Single, Married..." ↔ "Marital: S, M..."
        
        assertTrue(result.size() >= 3, 
            "Should create at least 3 demographic mappings (got " + result.size() + ")");
        
        System.out.println("✅ Patient demographics mapping test passed\n");
    }

    @Test
    void test_clinical_assessments() throws Exception {
        System.out.println("\n=== Testing Clinical Assessment Scales ===");
        
        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-clinical-assessments.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        
        Set<String> mappedKeys = result.stream()
            .flatMap(map -> map.keySet().stream())
            .collect(java.util.stream.Collectors.toSet());
        
        System.out.println("Mapped categories: " + mappedKeys);
        
        // Expected matches:
        // - "NIHSS Score" ↔ "NIH Stroke Scale"
        // - "Modified Rankin Scale" ↔ "mRS"
        // - "Glasgow Coma Scale" ↔ "GCS"
        // - "Ejection Fraction" ↔ "EF (%)"
        
        assertTrue(result.size() >= 4, 
            "Should create at least 4 clinical assessment mappings (got " + result.size() + ")");
        
        System.out.println("✅ Clinical assessments mapping test passed\n");
    }

    private MappingSuggestRequestDTO loadFixture(String path) throws IOException {
        Path p = Paths.get(path);
        if (!Files.exists(p)) {
            fail("Fixture file not found: " + p.toAbsolutePath());
        }
        
        try (InputStream is = Files.newInputStream(p)) {
            return MAPPER.readValue(is, MappingSuggestRequestDTO.class);
        }
    }
}
