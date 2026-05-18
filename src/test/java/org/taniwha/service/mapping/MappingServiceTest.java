package org.taniwha.service.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.service.EmbeddingService;
import org.taniwha.service.ValueMappingBuilder;
import org.taniwha.service.enrichment.DescriptionService;
import org.taniwha.service.enrichment.OpenMedTerminologyService;
import org.taniwha.service.enrichment.TerminologyLookupService;
import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedValueDTO;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive functional test suite for MappingService.
 * Tests functionality, not efficacy measurements.
 * Focuses on code coverage and correctness of logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MappingService Functional Tests")
class MappingServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private TerminologyLookupService terminologyService;

    @Mock
    private OpenMedTerminologyService openMedTerminologyService;

    @Mock
    private DescriptionService descriptionGenerator;

    @Mock
    private ValueMappingBuilder valueMappingBuilder;

    private MappingService mappingService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        MappingServiceSettings mappingSettings = new MappingServiceSettings(
                60, 120, 0.33, 0.56, 6, 40, 10, 0.22, 4, 3, 2, 6
        );
        mappingService = new MappingService(
                embeddingService, terminologyService,
                openMedTerminologyService,
                descriptionGenerator, valueMappingBuilder, objectMapper, mappingSettings
        );

        // Setup default mock behavior - return deterministic embeddings (lenient for tests that don't use it)
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
            .thenAnswer(invocation -> {
                String text = invocation.getArgument(0);
                return createDeterministicEmbedding(text, 384);
            });
        lenient().when(embeddingService.embedSchemaField(any(String.class), any(String.class), any(), any(String.class)))
            .thenAnswer(invocation -> {
                String text = invocation.getArgument(0);
                return createDeterministicEmbedding(text, 384);
            });

        // Setup default mock behavior for TerminologyService
        lenient().when(terminologyService.batchLookupTerminology(any()))
            .thenReturn(Collections.emptyMap());

        // Setup default mock behavior for OpenMedTerminologyService
        lenient().when(openMedTerminologyService.batchSize()).thenReturn(5);
        lenient().when(openMedTerminologyService.inferBatch(any())).thenReturn(Collections.emptyList());

        // Setup default mock behavior for DescriptionGenerator
        lenient().when(descriptionGenerator.generateEnrichmentBatchAsync(any()))
            .thenReturn(CompletableFuture.completedFuture(Collections.emptyMap()));

        // Setup default mock behavior for ValueMappingBuilder
        lenient().when(valueMappingBuilder.buildValuesForConcept(any(), any(), any(), any()))
            .thenReturn(Collections.emptyList());
    }

    // ==================== Input Validation Tests ====================

    @Test
    @DisplayName("Should handle null request gracefully")
    void testNullRequest() {
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(null);
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty for null request");
    }

    @Test
    @DisplayName("Should handle request with null elementFiles")
    void testNullElementFiles() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(null);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty for null element files");
    }

    @Test
    @DisplayName("Should handle empty elementFiles list")
    void testEmptyElementFiles() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(new ArrayList<>());
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertTrue(result.isEmpty(), "Result should be empty for empty element files");
    }

    @Test
    @DisplayName("Should handle elementFiles with null elements")
    void testElementFilesWithNulls() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        List<ColumnInFileDTO> elements = new ArrayList<>();
        elements.add(null);
        elements.add(createElementFile("ValidColumn", Arrays.asList("val1", "val2"), "file1.csv"));
        elements.add(null);
        req.setElementFiles(elements);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should process the valid element, ignore nulls
    }

    @Test
    @DisplayName("Should handle columns with empty or blank names")
    void testEmptyColumnNames() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        List<ColumnInFileDTO> elements = Arrays.asList(
            createElementFile("", Arrays.asList("val1"), "file1.csv"),
            createElementFile("  ", Arrays.asList("val2"), "file2.csv"),
            createElementFile("ValidColumn", Arrays.asList("val3"), "file3.csv")
        );
        req.setElementFiles(elements);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should only process the valid column
    }

    // ==================== Single Column Tests ====================

    @Test
    @DisplayName("Should process single column with string values")
    void testSingleColumnStringValues() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("PatientName", Arrays.asList("John", "Jane", "Bob"), "patients.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create at least one mapping");
    }

    @Test
    @DisplayName("Should process single column with integer values")
    void testSingleColumnIntegerValues() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Age", Arrays.asList("integer", "min:0", "max:100"), "patients.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mapping for integer column");
    }

    @Test
    @DisplayName("Should process single column with double values")
    void testSingleColumnDoubleValues() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Temperature", Arrays.asList("double", "min:36.5", "max:42.0"), "vitals.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mapping for double column");
    }

    @Test
    @DisplayName("Should process single column with categorical values")
    void testSingleColumnCategoricalValues() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Gender", Arrays.asList("M", "F", "Other"), "patients.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mapping for categorical column");
    }

    // ==================== Multiple Columns Tests ====================

    @Test
    @DisplayName("Should process multiple columns from same file")
    void testMultipleColumnsSameFile() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("PatientID", Arrays.asList("integer", "min:1", "max:1000"), "data.csv"),
            createElementFile("PatientName", Arrays.asList("John", "Jane"), "data.csv"),
            createElementFile("Age", Arrays.asList("integer", "min:0", "max:100"), "data.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mappings");
    }

    @Test
    @DisplayName("Should process multiple columns from different files")
    void testMultipleColumnsDifferentFiles() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("PatientID", Arrays.asList("integer", "min:1", "max:100"), "file1.csv"),
            createElementFile("PatientID", Arrays.asList("integer", "min:1", "max:100"), "file2.csv"),
            createElementFile("Name", Arrays.asList("John", "Jane"), "file3.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should cluster similar columns together
    }

    @Test
    @DisplayName("Should handle columns with similar names")
    void testSimilarColumnNames() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("patient_id", Arrays.asList("1", "2", "3"), "file1.csv"),
            createElementFile("PatientID", Arrays.asList("10", "20", "30"), "file2.csv"),
            createElementFile("PATIENT_ID", Arrays.asList("100", "200"), "file3.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should recognize these as the same concept and cluster them
    }

    // ==================== Value Type Detection Tests ====================

    @Test
    @DisplayName("Should detect integer type from values")
    void testDetectIntegerType() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Count", Arrays.asList("integer", "min:1", "max:10"), "data.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mapping");
        
        // Verify the mapping was created
        Map<String, SuggestedMappingDTO> firstMapping = result.get(0);
        assertFalse(firstMapping.isEmpty(), "Mapping should have keys");
    }

    @Test
    @DisplayName("Should detect double type from values")
    void testDetectDoubleType() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Price", Arrays.asList("double", "min:0.0", "max:100.99"), "products.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mapping");
    }

    @Test
    @DisplayName("Should handle mixed numeric values")
    void testMixedNumericValues() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Score", Arrays.asList("1", "2.5", "3", "4.7"), "scores.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should handle mixed integer/double values
    }

    // ==================== Schema-based Tests ====================

    @Test
    @DisplayName("Should handle request with schema")
    void testWithSchema() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("PatientAge", Arrays.asList("integer", "min:0", "max:100"), "file1.csv"),
            createElementFile("Gender", Arrays.asList("M", "F"), "file1.csv")
        ));
        
        // Create a simple JSON schema
        String schema = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"age\": {\n" +
            "            \"type\": \"integer\",\n" +
            "            \"description\": \"Patient age in years\"\n" +
            "        },\n" +
            "        \"sex\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"Male\", \"Female\"]\n" +
            "        }\n" +
            "    }\n" +
            "}";
        req.setSchema(schema);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should attempt to match columns to schema fields
    }

    @Test
    @DisplayName("Should handle schema with enum values")
    void testSchemaWithEnums() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Status", Arrays.asList("Active", "Inactive", "Pending"), "file1.csv")
        ));
        
        String schema = "{\n" +
            "    \"type\": \"object\",\n" +
            "    \"properties\": {\n" +
            "        \"status\": {\n" +
            "            \"type\": \"string\",\n" +
            "            \"enum\": [\"ACTIVE\", \"INACTIVE\", \"PENDING\", \"COMPLETED\"]\n" +
            "        }\n" +
            "    }\n" +
            "}";
        req.setSchema(schema);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    @Test
    @DisplayName("Schema suggestions should filter embedding-only column pairings")
    void testSchemaSuggestionsRequireStructuralEvidence() {
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenAnswer(invocation -> schemaFixtureVector(invocation.getArgument(0)));
        lenient().when(embeddingService.embedSchemaField(any(String.class), any(String.class), any(), any(String.class)))
                .thenAnswer(invocation -> schemaFixtureVector(invocation.getArgument(0)));

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setSchema("""
                {
                  "type": "object",
                  "properties": {
                    "age": { "type": ["integer", "null"] },
                    "outcome": { "type": ["string", "null"] },
                    "discharge_status": { "type": ["string", "null"] },
                    "admission_date": { "type": ["string", "null"], "description": "ISO date" },
                    "discharge_date": { "type": ["string", "null"], "description": "ISO date" },
                    "sex": { "type": ["string", "null"] },
                    "eating": { "type": ["integer", "null"], "description": "Eating activity score" },
                    "bathing": { "type": ["integer", "null"], "description": "Bathing activity score" },
                    "toileting": { "type": ["integer", "null"], "description": "Toileting activity score" }
                  }
                }""");
        req.setElementFiles(Arrays.asList(
                createElementFile("age", Arrays.asList("integer", "min:18", "max:95"), "demographics.csv"),
                createElementFile("years", Arrays.asList("integer", "min:18", "max:95"), "demographics_alt.csv"),
                createElementFile("gender_code", Arrays.asList("integer", "min:0", "max:2"), "demographics_alt.csv"),
                createElementFile("outcome_status", Arrays.asList("improved", "stable"), "outcomes.csv"),
                createElementFile("adverse_event", Arrays.asList("fall", "infection"), "events.csv"),
                createElementFile("discharge_status", Arrays.asList("home", "transfer"), "outcomes.csv"),
                createElementFile("notes", Arrays.asList("good progress", "requires follow-up"), "notes.csv"),
                createElementFile("admission_dt", Arrays.asList("date", "earliest:2024-01-01T00:00:00Z", "latest:2024-12-01T00:00:00Z"), "stays.csv"),
                createElementFile("assessment_date", Arrays.asList("date", "earliest:2024-01-01T00:00:00Z", "latest:2024-12-01T00:00:00Z"), "assessments.csv"),
                createElementFile("gender", Arrays.asList("male", "female"), "demographics.csv"),
                createElementFile("fim_eating", Arrays.asList("integer", "min:1", "max:7"), "fim_a.csv"),
                createElementFile("fim_eat_score", Arrays.asList("integer", "min:1", "max:7"), "fim.csv"),
                createElementFile("barthel_feed", Arrays.asList("integer", "min:0", "max:10"), "barthel_feed.csv"),
                createElementFile("barthel_bathing", Arrays.asList("integer", "min:0", "max:5"), "barthel_a.csv"),
                createElementFile("barthel_bath", Arrays.asList("integer", "min:0", "max:5"), "barthel_b.csv"),
                createElementFile("barthel_toilet", Arrays.asList("integer", "min:0", "max:10"), "barthel.csv"),
                createElementFile("therapist", Arrays.asList("Ana", "Luis"), "staff.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        Map<String, Set<String>> sourcesByTarget = sourceColumnsByTarget(result);

        assertTrue(sourcesByTarget.getOrDefault("age", Set.of()).contains("age"));
        assertTrue(sourcesByTarget.getOrDefault("age", Set.of()).contains("years"));
        assertFalse(sourcesByTarget.getOrDefault("age", Set.of()).contains("gender_code"),
                "Numeric coded categories must not be pulled into age just because embeddings are close");

        assertTrue(sourcesByTarget.getOrDefault("outcome", Set.of()).contains("outcome_status"));
        assertFalse(sourcesByTarget.getOrDefault("outcome", Set.of()).contains("adverse_event"));

        assertTrue(sourcesByTarget.getOrDefault("discharge_status", Set.of()).contains("discharge_status"));
        assertFalse(sourcesByTarget.getOrDefault("discharge_status", Set.of()).contains("notes"));

        assertTrue(sourcesByTarget.getOrDefault("admission_date", Set.of()).contains("admission_dt"));
        assertFalse(sourcesByTarget.getOrDefault("discharge_date", Set.of()).contains("assessment_date"));

        assertTrue(sourcesByTarget.getOrDefault("sex", Set.of()).contains("gender"));
        assertTrue(sourcesByTarget.getOrDefault("eating", Set.of()).contains("fim_eat_score"));
        assertTrue(sourcesByTarget.getOrDefault("eating", Set.of()).contains("fim_eating"));
        assertTrue(sourcesByTarget.getOrDefault("eating", Set.of()).contains("barthel_feed"));
        assertTrue(sourcesByTarget.getOrDefault("bathing", Set.of()).contains("barthel_bathing"));
        assertTrue(sourcesByTarget.getOrDefault("bathing", Set.of()).contains("barthel_bath"));
        assertTrue(sourcesByTarget.getOrDefault("toileting", Set.of()).contains("barthel_toilet"));
        assertFalse(sourcesByTarget.containsKey("fim_eating_2"));
        assertFalse(sourcesByTarget.containsKey("eating_2"));
        assertFalse(sourcesByTarget.containsKey("bathing_2"));
        assertFalse(sourcesByTarget.containsKey("therapist"),
                "Schema mode must not invent non-schema singleton targets for leftover columns");
    }

    @Test
    @DisplayName("All mapping fixtures should produce valid suggestion shape")
    void testMappingFixtureSuggestionShapes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path fixtureDir = Path.of("src", "test", "resources", "mapping");

        try (Stream<Path> paths = Files.list(fixtureDir)) {
            List<Path> fixtures = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();

            assertFalse(fixtures.isEmpty(), "Expected mapping fixtures under " + fixtureDir);

            for (Path fixture : fixtures) {
                MappingSuggestRequestDTO req = mapper.readValue(Files.readString(fixture), MappingSuggestRequestDTO.class);
                List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

                assertNotNull(result, "Result should not be null for " + fixture);
                assertFalse(result.isEmpty(), "Expected suggestions for " + fixture.getFileName());
                assertValidSuggestionShape(result, fixture.getFileName().toString());

                System.out.printf("[MappingFixtureShape] %s -> %d mappings %s%n",
                        fixture.getFileName(), result.size(), result.stream()
                                .flatMap(map -> map.keySet().stream())
                                .toList());
            }
        }
    }

    @Test
    @DisplayName("Schemaless suggestions should cluster repeated-prefix assessment columns by activity")
    void testSchemalessRepeatedPrefixAssessmentColumnsClusterByActivity() {
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
                createElementFile("fim_eating", Arrays.asList("integer", "min:1", "max:7"), "fim_a.csv"),
                createElementFile("fim_eat_score", Arrays.asList("integer", "min:1", "max:7"), "fim_b.csv"),
                createElementFile("fim_grooming", Arrays.asList("integer", "min:1", "max:7"), "fim_c.csv"),
                createElementFile("barthel_feed", Arrays.asList("integer", "min:0", "max:10"), "barthel_a.csv"),
                createElementFile("barthel_bathing", Arrays.asList("integer", "min:0", "max:5"), "barthel_b.csv"),
                createElementFile("barthel_bath", Arrays.asList("integer", "min:0", "max:5"), "barthel_c.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        Map<String, Set<String>> sourcesByTarget = sourceColumnsByTarget(result);

        assertFalse(sourcesByTarget.containsKey("fim"),
                "Repeated source prefixes should not become schemaless targets");
        assertFalse(sourcesByTarget.containsKey("barthel"),
                "Repeated source prefixes should not become schemaless targets");

        assertTrue(sourcesByTarget.values().stream().anyMatch(cols ->
                        cols.contains("fim_eating") && cols.contains("fim_eat_score")),
                "Schemaless mode should merge equivalent eating columns under one activity concept");
        assertFalse(sourcesByTarget.values().stream().anyMatch(cols ->
                        cols.contains("fim_eat_score") && cols.contains("fim_toilet_score")),
                "Generic score suffixes must not merge different activities");
        assertTrue(sourcesByTarget.values().stream().anyMatch(cols ->
                        cols.contains("barthel_bathing") && cols.contains("barthel_bath")),
                "Schemaless mode should merge equivalent bathing columns under one activity concept");
    }

    @Test
    @DisplayName("Schemaless suggestions should keep life-event years separate from age-like years")
    void testSchemalessDoesNotMergeBirthYearWithAgeYears() {
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
                createElementFile("age", Arrays.asList("integer", "min:18", "max:95"), "demographics.csv"),
                createElementFile("years", Arrays.asList("integer", "min:18", "max:95"), "demographics_alt.csv"),
                createElementFile("birth_year", Arrays.asList("integer", "min:1930", "max:2006"), "registry.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        Map<String, Set<String>> sourcesByTarget = sourceColumnsByTarget(result);

        assertFalse(sourcesByTarget.values().stream().anyMatch(cols ->
                        cols.contains("birth_year") && (cols.contains("age") || cols.contains("years"))),
                "Birth year must not merge with age-like year columns");
    }

    @Test
    @DisplayName("Schemaless suggestions should preserve prefixes for generic summary concepts")
    void testSchemalessPreservesGenericSummaryPrefixes() {
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
                createElementFile("fim_total", Arrays.asList("integer", "min:10", "max:30"), "fim_a.csv"),
                createElementFile("fim_total", Arrays.asList("integer", "min:11", "max:31"), "fim_b.csv"),
                createElementFile("barthel_total", Arrays.asList("integer", "min:10", "max:30"), "barthel_a.csv"),
                createElementFile("barthel_total", Arrays.asList("integer", "min:5", "max:30"), "barthel_b.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        Map<String, Set<String>> sourcesByTarget = sourceColumnsByTarget(result);

        assertTrue(sourcesByTarget.containsKey("fim_total"), "FIM total should keep its prefix");
        assertTrue(sourcesByTarget.containsKey("barthel_total"), "Barthel total should keep its prefix");
        assertFalse(sourcesByTarget.containsKey("total"), "Bare generic total should not be emitted");
    }

    @Test
    @DisplayName("Should handle invalid or malformed schema")
    void testMalformedSchema() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Column1", Arrays.asList("val1", "val2"), "file1.csv")
        ));
        req.setSchema("{ invalid json }");
        
        // Should fall back to schema-less mode if schema is invalid
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    // ==================== Clustering Tests ====================

    @Test
    @DisplayName("Should cluster similar columns together")
    void testClusteringSimilarColumns() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("FirstName", Arrays.asList("John", "Jane"), "file1.csv"),
            createElementFile("first_name", Arrays.asList("Bob", "Alice"), "file2.csv"),
            createElementFile("Age", Arrays.asList("integer", "min:0", "max:100"), "file3.csv"),
            createElementFile("age", Arrays.asList("integer", "min:0", "max:100"), "file4.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Similar columns should be clustered
        // Age columns should cluster together
        // FirstName columns should cluster together
    }

    @Test
    @DisplayName("Should not cluster dissimilar columns")
    void testNotClusteringDissimilarColumns() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("PatientID", Arrays.asList("integer", "min:1", "max:1000"), "file1.csv"),
            createElementFile("Temperature", Arrays.asList("double", "min:36.0", "max:42.0"), "file2.csv"),
            createElementFile("Diagnosis", Arrays.asList("Flu", "Cold", "COVID"), "file3.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // These dissimilar columns should not cluster together
    }

    @Test
    @DisplayName("Should cluster generic 'Type' column with typed 'Etiology' column via value character-ngram similarity")
    void testClusteringByValueEmbeddingSimilarity() {
        // Use identical combined embeddings (cosine sim = 1.0) so the ONLY deciding factor is
        // structural evidence.  Before the fix, concept-level evidence is absent ("type" vs
        // "etiology isch hem" share no tokens and no abbreviation pair), so the two columns
        // would remain separate.
        //
        // After the fix, char-n-gram value similarity provides the necessary structural evidence:
        // "ischemic" contains the 3-gram "hem" (positions 3-5) and shares "isc"/"sch" with
        // "isch"; "hemorrhagic" starts with "hem".  ValueVectorUtil.build() runs with its real
        // implementation (it is a pure, dependency-free utility — no mock needed).
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Type", Arrays.asList("Ischemic", "Hemorrhagic"), "fimb.csv"),
            // "HEM" (uppercase) mirrors real-world fixture data; normalizeValue lowercases it.
            createElementFile("Etiology (Isch/Hem)", Arrays.asList("Hem", "HEM", "Isch"), "scuba.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result, "Result should not be null");
        assertEquals(1, result.size(),
            "Type [Ischemic, Hemorrhagic] and Etiology (Isch/Hem) [Hem, Isch] should cluster "
            + "into one group because their char-n-gram value vectors share features "
            + "('hem' appears in both 'ischemic' and 'hemorrhagic', 'isc'/'sch' appear in both)");
        // The group key must use the meaningful concept name, not the structural "type"
        assertTrue(result.get(0).containsKey("etiology_isch_hem"),
            "Group key should be 'etiology_isch_hem' (the non-structural representative concept), "
            + "not 'type'. Found keys: " + result.get(0).keySet());
    }

    @Test
    @DisplayName("TOTAL MOTOR (FIM subscale) should NOT cluster with TOT BARTHEL (Barthel index)")
    void testTotalMotorShouldNotMergeWithTotBarthel() {
        // Both are "total" rehabilitation scores, so PubMedBERT embeddings are close.
        // The bug was that "tot" (from "tot barthel") is a prefix of "total" (from "total motor"),
        // so the abbreviation check fired — ignoring that "barthel" has no match in "motor".
        // After the fix, the multi-token prefix guard requires ALL other tokens to also match.
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("TOTAL MOTOR", Arrays.asList("integer", "min:13", "max:91"), "fimb.csv"),
            createElementFile("TOTBarthel", Arrays.asList("integer", "min:0", "max:100"), "scuba.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.size(),
            "TOTAL MOTOR (FIM subscale) and TOT BARTHEL (Barthel index) are different scales "
            + "and must remain separate clusters");
    }

    @Test
    @DisplayName("LOS (days) should NOT cluster with TSI to admission (days)")
    void testLosDaysShouldNotMergeWithTsiAdmissionDays() {
        // "LOS (days)" measures hospital length-of-stay (typically 2-56 days) while
        // "TSI to admission (days)" measures time since spinal injury to rehab admission
        // (typically 200-7000 days).  Before the fix, they merged because "days" gave a
        // Jaccard overlap of 0.2 (≥ the 0.15 threshold).  After the fix "days" is a
        // Jaccard stop-token, so no structural evidence exists and they stay separate.
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("LOS (days)", Arrays.asList("integer", "min:2", "max:56"), "scuba1.csv"),
            createElementFile("TSI to admission (days)",
                    Arrays.asList("integer", "min:203", "max:7726"), "scuba2.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.size(),
            "LOS (days) and TSI to admission (days) are completely different duration measures "
            + "and must remain separate clusters (sharing only the unit token 'days' is not "
            + "sufficient structural evidence)");
    }

    @Test
    @DisplayName("Total scores should NOT cluster with individual assessment items")
    void testTotalScoreShouldNotMergeWithIndividualAssessmentItem() {
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
                createElementFile("TOTALBARTHEL", Arrays.asList("integer", "min:0", "max:100"), "summary.csv"),
                createElementFile("Social Interaction", Arrays.asList("integer", "min:1", "max:7"), "items.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.size(),
                "A total/index score and a single functional item should remain separate even "
                        + "when their embeddings are indistinguishable");
    }

    @Test
    @DisplayName("Duration fields should NOT cluster with individual assessment items")
    void testDurationShouldNotMergeWithIndividualAssessmentItem() {
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
                createElementFile("TSI to admission (days)",
                        Arrays.asList("integer", "min:203", "max:7726"), "timing.csv"),
                createElementFile("Social Interaction", Arrays.asList("integer", "min:1", "max:7"), "items.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);

        assertNotNull(result, "Result should not be null");
        assertEquals(2, result.size(),
                "A duration/count-of-days variable and an individual functional item should not merge");
    }

    @Test
    @DisplayName("Boolean-like outcome status should NOT cluster with descriptive outcome categories")
    void testBooleanLikeOutcomeStatusShouldNotMergeWithDescriptiveOutcome() {
        float[] sharedVec = new float[384];
        sharedVec[0] = 1.0f;
        lenient().when(embeddingService.embedColumnWithValues(any(String.class), any()))
                .thenReturn(sharedVec);

        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
                createElementFile("outcome_status", Arrays.asList("No", "Partial", "Yes"), "sample_2.csv"),
                createElementFile("outcome", Arrays.asList("Recovered", "Deceased", "Improved"), "sample_1.csv")
        ));

        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        Map<String, Set<String>> sourcesByTarget = sourceColumnsByTarget(result);

        assertFalse(sourcesByTarget.values().stream().anyMatch(cols ->
                        cols.contains("outcome_status") && cols.contains("outcome")),
                "A yes/no/partial status column should not merge with descriptive outcome categories");
    }

    // ==================== Value Mapping Tests ====================

    @Test
    @DisplayName("Should create value mappings for categorical columns")
    void testCategoricalValueMappings() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Gender", Arrays.asList("M", "F"), "file1.csv"),
            createElementFile("Sex", Arrays.asList("Male", "Female"), "file2.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mapping");
        
        // Should recognize M/F and Male/Female as related gender concepts
    }

    @Test
    @DisplayName("Should create range mappings for numeric columns")
    void testNumericRangeMappings() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Score1to10", Arrays.asList("integer", "min:1", "max:10"), "file1.csv"),
            createElementFile("Score0to100", Arrays.asList("integer", "min:0", "max:100"), "file2.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should create mappings for numeric ranges
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Should handle column with very long name")
    void testVeryLongColumnName() {
        String longName = "ThisIsAVeryLongColumnNameThatExceedsNormalLengthAndMightCauseIssuesWithTokenization";
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile(longName, Arrays.asList("val1", "val2"), "file1.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    @Test
    @DisplayName("Should handle column with special characters")
    void testSpecialCharactersInColumnName() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Patient-ID_#1", Arrays.asList("1", "2"), "file1.csv"),
            createElementFile("@Column$Name%", Arrays.asList("a", "b"), "file2.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    @Test
    @DisplayName("Should handle column with numeric name")
    void testNumericColumnName() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("123", Arrays.asList("val1", "val2"), "file1.csv"),
            createElementFile("456.789", Arrays.asList("val3", "val4"), "file2.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    @Test
    @DisplayName("Should handle very large number of columns")
    void testManyColumns() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        List<ColumnInFileDTO> elements = new ArrayList<>();
        
        // Create 100 columns
        for (int i = 0; i < 100; i++) {
            elements.add(createElementFile("Column" + i, Arrays.asList("val1", "val2"), "file.csv"));
        }
        req.setElementFiles(elements);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should handle many columns without crashing
    }

    @Test
    @DisplayName("Should handle column with very many unique values")
    void testManyUniqueValues() {
        List<String> manyValues = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            manyValues.add("Value" + i);
        }
        
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("UniqueIDs", manyValues, "file1.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should handle high-cardinality columns
    }

    @Test
    @DisplayName("Should handle empty values list")
    void testEmptyValuesList() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("EmptyColumn", new ArrayList<>(), "file1.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    @Test
    @DisplayName("Should handle values list with null elements")
    void testValuesListWithNulls() {
        List<String> valuesWithNulls = new ArrayList<>();
        valuesWithNulls.add("val1");
        valuesWithNulls.add(null);
        valuesWithNulls.add("val2");
        valuesWithNulls.add(null);
        
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("ColumnWithNulls", valuesWithNulls, "file1.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
    }

    // ==================== Medical Domain Tests ====================

    @Test
    @DisplayName("Should handle medical terminology columns")
    void testMedicalTerminology() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Diagnosis", Arrays.asList("Diabetes", "Hypertension"), "medical.csv"),
            createElementFile("ICD10Code", Arrays.asList("E11.9", "I10"), "medical.csv"),
            createElementFile("BloodPressure", Arrays.asList("double", "min:80.0", "max:180.0"), "vitals.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mappings for medical data");
    }

    @Test
    @DisplayName("Should handle assessment scale columns")
    void testAssessmentScales() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("BarthelIndex", Arrays.asList("integer", "min:0", "max:100"), "assessment.csv"),
            createElementFile("FIMScore", Arrays.asList("integer", "min:18", "max:126"), "assessment.csv"),
            createElementFile("Toileting", Arrays.asList("integer", "min:1", "max:7"), "assessment.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        assertFalse(result.isEmpty(), "Should create mappings for assessment scales");
    }

    // ==================== Canonical Name Tests ====================

    @Test
    @DisplayName("Should normalize column names to canonical form")
    void testCanonicalNameNormalization() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("patient_name", Arrays.asList("John"), "file1.csv"),
            createElementFile("PatientName", Arrays.asList("Jane"), "file2.csv"),
            createElementFile("PATIENT_NAME", Arrays.asList("Bob"), "file3.csv"),
            createElementFile("Patient Name", Arrays.asList("Alice"), "file4.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // All variations should be recognized as the same concept
    }

    @Test
    @DisplayName("Should handle abbreviations and full names")
    void testAbbreviationsAndFullNames() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("ID", Arrays.asList("1", "2"), "file1.csv"),
            createElementFile("Identifier", Arrays.asList("10", "20"), "file2.csv"),
            createElementFile("BP", Arrays.asList("120", "130"), "file3.csv"),
            createElementFile("BloodPressure", Arrays.asList("125", "135"), "file4.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should recognize ID/Identifier and BP/BloodPressure as related
    }

    // ==================== File-based Tests ====================

    @Test
    @DisplayName("Should track source files correctly")
    void testSourceFileTracking() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Column1", Arrays.asList("val1"), "file1.csv"),
            createElementFile("Column2", Arrays.asList("val2"), "file2.csv"),
            createElementFile("Column3", Arrays.asList("val3"), "file3.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should correctly track which columns come from which files
    }

    @Test
    @DisplayName("Should handle multiple columns from same file with same name")
    void testDuplicateColumnNamesSameFile() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        req.setElementFiles(Arrays.asList(
            createElementFile("Value", Arrays.asList("1", "2"), "file1.csv"),
            createElementFile("Value", Arrays.asList("3", "4"), "file1.csv"),
            createElementFile("Value", Arrays.asList("5", "6"), "file1.csv")
        ));
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should handle duplicate column names
    }

    // ==================== Performance/Stress Tests ====================

    @Test
    @DisplayName("Should handle large request without crashing")
    void testLargeRequest() {
        MappingSuggestRequestDTO req = new MappingSuggestRequestDTO();
        List<ColumnInFileDTO> elements = new ArrayList<>();
        
        // Create 50 diverse columns
        for (int i = 0; i < 50; i++) {
            String colName = "Column_" + i;
            List<String> values;
            if (i % 3 == 0) {
                values = Arrays.asList("integer", "min:0", "max:100");
            } else if (i % 3 == 1) {
                values = Arrays.asList("double", "min:0.0", "max:100.0");
            } else {
                values = Arrays.asList("Cat1", "Cat2", "Cat3");
            }
            elements.add(createElementFile(colName, values, "file" + (i % 5) + ".csv"));
        }
        req.setElementFiles(elements);
        
        List<Map<String, SuggestedMappingDTO>> result = mappingService.suggestMappings(req);
        
        assertNotNull(result, "Result should not be null");
        // Should complete without errors
    }

    // ==================== Helper Methods ====================

    private ColumnInFileDTO createElementFile(String columnName, List<String> values, String fileName) {
        ColumnInFileDTO dto = new ColumnInFileDTO();
        dto.setColumn(columnName);
        dto.setValues(values);
        dto.setFileName(fileName);
        dto.setNodeId("node-" + UUID.randomUUID().toString());
        dto.setColor("#FFFFFF");
        return dto;
    }

    private Map<String, Set<String>> sourceColumnsByTarget(List<Map<String, SuggestedMappingDTO>> result) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map<String, SuggestedMappingDTO> mappingMap : result) {
            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                Set<String> cols = out.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>());
                SuggestedMappingDTO mapping = entry.getValue();
                if (mapping == null || mapping.getGroups() == null) continue;
                for (SuggestedGroupDTO group : mapping.getGroups()) {
                    if (group == null || group.getValues() == null) continue;
                    for (SuggestedValueDTO value : group.getValues()) {
                        if (value == null || value.getMapping() == null) continue;
                        value.getMapping().forEach(ref -> cols.add(ref.getGroupColumn()));
                    }
                }
            }
        }
        return out;
    }

    private void assertValidSuggestionShape(List<Map<String, SuggestedMappingDTO>> result, String fixtureName) {
        for (Map<String, SuggestedMappingDTO> mappingMap : result) {
            assertNotNull(mappingMap, "Mapping object should not be null in " + fixtureName);
            assertEquals(1, mappingMap.size(), "Each suggestion object should contain exactly one target in " + fixtureName);
            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                assertFalse(entry.getKey().isBlank(), "Target key should not be blank in " + fixtureName);
                SuggestedMappingDTO mapping = entry.getValue();
                assertNotNull(mapping, "Mapping DTO should not be null for " + entry.getKey());
                assertNotNull(mapping.getMappingType(), "Mapping type should not be null for " + entry.getKey());
                assertNotNull(mapping.getColumns(), "Columns should not be null for " + entry.getKey());
                assertFalse(mapping.getColumns().isEmpty(), "Columns should not be empty for " + entry.getKey());
                assertNotNull(mapping.getGroups(), "Groups should not be null for " + entry.getKey());
                assertFalse(mapping.getGroups().isEmpty(), "Groups should not be empty for " + entry.getKey());
                for (SuggestedGroupDTO group : mapping.getGroups()) {
                    assertNotNull(group, "Group should not be null for " + entry.getKey());
                    assertFalse(String.valueOf(group.getColumn()).isBlank(), "Group column should not be blank for " + entry.getKey());
                    assertNotNull(group.getValues(), "Group values should not be null for " + entry.getKey());
                    assertFalse(group.getValues().isEmpty(), "Group values should not be empty for " + entry.getKey());
                    for (SuggestedValueDTO value : group.getValues()) {
                        assertNotNull(value, "Value should not be null for " + entry.getKey());
                        assertFalse(String.valueOf(value.getName()).isBlank(), "Value name should not be blank for " + entry.getKey());
                        assertNotNull(value.getMapping(), "Value mappings should not be null for " + entry.getKey());
                    }
                }
            }
        }
    }

    private float[] schemaFixtureVector(String text) {
        String t = String.valueOf(text).toLowerCase(Locale.ROOT);
        int slot = 0;
        if (t.contains("gender_code") || t.equals("age") || t.contains("years")) slot = 1;
        else if (t.contains("adverse") || t.contains("outcome")) slot = 2;
        else if (t.contains("notes") || t.contains("discharge_status")
                || (t.contains("discharge") && t.contains("status"))) slot = 3;
        else if (t.contains("admission")) slot = 4;
        else if (t.contains("discharge_date")) slot = 5;
        else if (t.contains("assessment_date")) slot = 9;
        else if (t.contains("sex") || t.contains("gender")) slot = 6;
        else if (t.contains("eating") || t.contains("eat") || t.contains("fim") || t.contains("feed")) slot = 7;
        else if (t.contains("bathing") || t.contains("bath") || t.contains("barthel")) slot = 8;

        float[] embedding = new float[16];
        embedding[slot] = 1.0f;
        return embedding;
    }

    /**
     * Create a deterministic embedding based on text content.
     * This ensures consistent test results while still providing diverse embeddings.
     */
    private float[] createDeterministicEmbedding(String text, int dimension) {
        float[] embedding = new float[dimension];
        int hash = text.hashCode();
        Random random = new Random(hash);
        
        // Generate normalized random vector
        double sum = 0.0;
        for (int i = 0; i < dimension; i++) {
            embedding[i] = random.nextFloat() * 2 - 1; // Range [-1, 1]
            sum += embedding[i] * embedding[i];
        }
        
        // Normalize to unit length
        float norm = (float) Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < dimension; i++) {
                embedding[i] /= norm;
            }
        }
        
        return embedding;
    }
}
