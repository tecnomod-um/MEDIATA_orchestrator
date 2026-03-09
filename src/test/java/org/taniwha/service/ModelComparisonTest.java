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

        @Bean(destroyMethod = "shutdown")
        public java.util.concurrent.ExecutorService llmExecutor() {
            return java.util.concurrent.Executors.newFixedThreadPool(8);
        }

        @Bean
        public EmbeddingsClient embeddingsClient(EmbeddingModel embeddingModel) {
            return new EmbeddingsClient(embeddingModel);
        }

        @Bean
        public EmbeddingService embeddingService(EmbeddingsClient embeddingsClient) {
            return new EmbeddingService(embeddingsClient);
        }

        @Bean
        public TerminologyLookupService terminologyService() {
            TerminologyLookupService mock = Mockito.mock(TerminologyLookupService.class);
            Mockito.when(mock.batchLookupTerminology(Mockito.any()))
                .thenReturn(Collections.emptyMap());
            return mock;
        }

        @Bean
        public TerminologyTermInferenceService terminologyTermInferenceService() {
            TerminologyTermInferenceService mock = Mockito.mock(TerminologyTermInferenceService.class);
            Mockito.when(mock.batchSize()).thenReturn(5);
            Mockito.when(mock.inferBatch(Mockito.any())).thenReturn(Collections.emptyList());
            return mock;
        }

        @Bean
        public LLMTextGenerator llmTextGenerator() {
            LLMTextGenerator mock = Mockito.mock(LLMTextGenerator.class);
            Mockito.when(mock.isEnabled()).thenReturn(false);
            return mock;
        }

        @Bean
        public DescriptionService descriptionGenerator(LLMTextGenerator llmTextGenerator,
                                                       java.util.concurrent.ExecutorService llmExecutor) {
            return new DescriptionService(llmTextGenerator, llmExecutor);
        }

        @Bean
        public ValueMappingBuilder valueMappingBuilder(EmbeddingService embeddingService) {
            return new ValueMappingBuilder(embeddingService);
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        public org.taniwha.config.MappingConfig.MappingServiceSettings mappingSettings() {
            return new org.taniwha.config.MappingConfig.MappingServiceSettings(
                    60, 120, 0.33, 0.56, 6, 40, 10, 0.22, 4, 3, 2, 6
            );
        }

        @Bean
        public MappingService mappingService(EmbeddingService embeddingService,
                                             TerminologyLookupService terminologyService,
                                             TerminologyTermInferenceService terminologyTermInferenceService,
                                             DescriptionService descriptionGenerator,
                                             ValueMappingBuilder valueMappingBuilder,
                                             ObjectMapper objectMapper,
                                             org.taniwha.config.MappingConfig.MappingServiceSettings mappingSettings) {
            return new MappingService(embeddingService, terminologyService, terminologyTermInferenceService,
                    descriptionGenerator, valueMappingBuilder, objectMapper, mappingSettings);
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
        printGroupDetails(result);

        // 6 distinct source columns → should produce at most 6 groups (no inflation)
        assertTrue(result.size() >= 2 && result.size() <= 6,
                "Expected 2–6 ICD-10 groups (got " + result.size() + ")");

        System.out.println("✅ ICD-10 mapping test passed\n");
    }

    @Test
    void test_vital_signs_abbreviations() throws Exception {
        System.out.println("\n=== Testing Vital Signs Abbreviations ===");

        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-vitals.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        printGroupDetails(result);

        // Pairs that reliably merge with all-MiniLM-L6-v2 (initialism + sufficient embedding sim):
        // - "HR (bpm)" ↔ "Heart Rate"  (HR is initialism of Heart Rate)
        // - "RR (breaths/min)" ↔ "Respiratory Rate"  (RR is initialism of Respiratory Rate)
        // - "Temp (C)" ↔ "Body Temperature"  (temp is a strict prefix of temperature)
        assertTrue(areGroupedTogether(result, "HR (bpm)", "Heart Rate"),
                "HR (bpm) and Heart Rate should be in the same mapping group");
        assertTrue(areGroupedTogether(result, "RR (breaths/min)", "Respiratory Rate"),
                "RR (breaths/min) and Respiratory Rate should be in the same mapping group");
        assertTrue(areGroupedTogether(result, "Temp (C)", "Body Temperature"),
                "Temp (C) and Body Temperature should be in the same mapping group");

        // 10 source columns, 5 pairs → ideal is 5 groups; allow up to 8 for model limitations
        assertTrue(result.size() >= 3 && result.size() <= 8,
                "Expected 3–8 vital-sign groups (got " + result.size() + ")");

        System.out.println("✅ Vital signs mapping test passed\n");
    }

    @Test
    void test_medications_brand_vs_generic() throws Exception {
        System.out.println("\n=== Testing Medication Mappings (Brand vs Generic) ===");

        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-medications.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        printGroupDetails(result);

        // "Dose" ↔ "Dosage" merges reliably (embedding similarity ≥ 0.80)
        assertTrue(areGroupedTogether(result, "Dose", "Dosage"),
                "Dose and Dosage should be in the same mapping group");

        // 6 source columns, 3 pairs → ideal is 3 groups; allow up to 6
        assertTrue(result.size() >= 2 && result.size() <= 6,
                "Expected 2–6 medication groups (got " + result.size() + ")");

        System.out.println("✅ Medication mapping test passed\n");
    }

    @Test
    void test_lab_results_abbreviations() throws Exception {
        System.out.println("\n=== Testing Lab Results Abbreviations ===");

        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-lab-results.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        printGroupDetails(result);

        // Pairs that reliably merge (initialism + embedding similarity):
        // - "WBC" ↔ "White Blood Cell Count"  (WBC is initialism of White Blood Cell Count)
        // - "Cr" ↔ "Serum Creatinine"  (Cr is prefix of Creatinine, and values match)
        // - "Total Cholesterol" ↔ "Cholesterol Total"  (word reordering, high embedding sim)
        assertTrue(areGroupedTogether(result, "WBC", "White Blood Cell Count"),
                "WBC and White Blood Cell Count should be in the same mapping group");
        assertTrue(areGroupedTogether(result, "Cr", "Serum Creatinine"),
                "Cr and Serum Creatinine should be in the same mapping group");
        assertTrue(areGroupedTogether(result, "Total Cholesterol", "Cholesterol Total"),
                "Total Cholesterol and Cholesterol Total should be in the same mapping group");

        // 12 source columns, 6 pairs → ideal is 6 groups; allow up to 10 for model limitations
        // (Note: LDL/HDL may co-merge due to high lipoprotein embedding similarity)
        assertTrue(result.size() >= 3 && result.size() <= 10,
                "Expected 3–10 lab-result groups (got " + result.size() + ")");

        System.out.println("✅ Lab results mapping test passed\n");
    }

    @Test
    void test_patient_demographics() throws Exception {
        System.out.println("\n=== Testing Patient Demographics ===");

        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-patient-demographics.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        printGroupDetails(result);

        // Pairs that reliably merge:
        // - "DOB" ↔ "Date of Birth"  (DOB is initialism of Date Of Birth)
        // - "Race/Ethnicity" ↔ "Ethnicity"  (shared token + high embedding similarity)
        assertTrue(areGroupedTogether(result, "DOB", "Date of Birth"),
                "DOB and Date of Birth should be in the same mapping group");
        assertTrue(areGroupedTogether(result, "Race/Ethnicity", "Ethnicity"),
                "Race/Ethnicity and Ethnicity should be in the same mapping group");

        // 10 source columns, 5 pairs → ideal is 5 groups; allow up to 9 for model limitations
        assertTrue(result.size() >= 2 && result.size() <= 9,
                "Expected 2–9 demographic groups (got " + result.size() + ")");

        System.out.println("✅ Patient demographics mapping test passed\n");
    }

    @Test
    void test_clinical_assessments() throws Exception {
        System.out.println("\n=== Testing Clinical Assessment Scales ===");

        MappingSuggestRequestDTO req = loadFixture("src/test/resources/mapping/fixture-medical-clinical-assessments.json");
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result);
        System.out.println("Total mappings: " + result.size());
        printGroupDetails(result);

        // Pairs that reliably merge:
        // - "NIHSS Score" ↔ "NIH Stroke Scale"  ("NIH" is strict prefix of "NIHSS" token)
        // - "Pain Score" ↔ "Pain Level (0-10)"  (high embedding similarity, share "pain" token)
        assertTrue(areGroupedTogether(result, "NIHSS Score", "NIH Stroke Scale"),
                "NIHSS Score and NIH Stroke Scale should be in the same mapping group");
        assertTrue(areGroupedTogether(result, "Pain Score", "Pain Level (0-10)"),
                "Pain Score and Pain Level (0-10) should be in the same mapping group");

        // 10 source columns, 5 pairs → ideal is 5 groups; allow up to 9 for model limitations
        assertTrue(result.size() >= 2 && result.size() <= 9,
                "Expected 2–9 clinical-assessment groups (got " + result.size() + ")");

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

    /**
     * Prints each mapping group with its constituent original column names.
     * This diagnostic output is visible only during test execution (stdout) and helps
     * understand which source columns the clustering algorithm placed in each group.
     */
    private static void printGroupDetails(List<Map<String, SuggestedMappingDTO>> result) {
        for (Map<String, SuggestedMappingDTO> m : result) {
            Map.Entry<String, SuggestedMappingDTO> e = m.entrySet().iterator().next();
            List<String> cols = e.getValue().getColumns();
            System.out.println("  [" + e.getKey() + "] <- " + cols);
        }
    }

    /**
     * Returns true when two source column names (exactly as written in the fixture) appear together
     * in the same mapping group's columns list.
     */
    private static boolean areGroupedTogether(List<Map<String, SuggestedMappingDTO>> result,
                                               String colA, String colB) {
        for (Map<String, SuggestedMappingDTO> m : result) {
            SuggestedMappingDTO dto = m.values().iterator().next();
            List<String> cols = dto.getColumns();
            if (cols != null && cols.contains(colA) && cols.contains(colB)) return true;
        }
        return false;
    }
}
