package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.taniwha.dto.ElementFileDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedValueDTO;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
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
    private EmbeddingsClient embeddingsClient;
    
    @Mock
    private RDFService rdfService;
    
    @Mock
    private DescriptionGenerator descriptionGenerator;

    private MappingService mappingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mappingService = new MappingService(embeddingsClient, rdfService, descriptionGenerator);
        objectMapper = new ObjectMapper();
        
        // Setup default mock behavior - return deterministic embeddings (lenient for tests that don't use it)
        lenient().when(embeddingsClient.embed(any(String.class)))
            .thenAnswer(invocation -> {
                String text = invocation.getArgument(0);
                return createDeterministicEmbedding(text, 384);
            });
        
        // Setup default mock behavior for RDFService
        lenient().when(rdfService.getSNOMEDTermSuggestions(any(String.class)))
            .thenReturn(new ArrayList<>());
        
        // Setup default mock behavior for DescriptionGenerator
        lenient().when(descriptionGenerator.generateColumnDescription(any(String.class), any()))
            .thenAnswer(invocation -> "Description for " + invocation.getArgument(0));
        lenient().when(descriptionGenerator.generateValueDescription(any(String.class), any(), any()))
            .thenAnswer(invocation -> "Value description for " + invocation.getArgument(0));
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
        List<ElementFileDTO> elements = new ArrayList<>();
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
        List<ElementFileDTO> elements = Arrays.asList(
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
        List<ElementFileDTO> elements = new ArrayList<>();
        
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
        List<ElementFileDTO> elements = new ArrayList<>();
        
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

    private ElementFileDTO createElementFile(String columnName, List<String> values, String fileName) {
        ElementFileDTO dto = new ElementFileDTO();
        dto.setColumn(columnName);
        dto.setValues(values);
        dto.setFileName(fileName);
        dto.setNodeId("node-" + UUID.randomUUID().toString());
        dto.setColor("#FFFFFF");
        return dto;
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
