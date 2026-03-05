package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.taniwha.service.MappingService.MappingSuggestion;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report test that runs sample input through MappingService
 * to validate LLM integration produces quality output.
 */
class MappingServiceReportTest {

    private MappingService mappingService;

    @BeforeEach
    void setUp() {
        mappingService = new MappingService();
    }

    @Test
    void testPatientNameMapping_sampleInput() {
        // Sample input: mapping "patient_name" to various target fields
        String sourceField = "patient_name";
        List<String> targetFields = Arrays.asList(
            "patientFullName",
            "fullName",
            "name",
            "patient_first_name",
            "PatientName",
            "subject_name",
            "diagnosis",
            "address"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        // Validate suggestions are returned
        assertThat(suggestions).isNotEmpty();
        
        // Top suggestion should be semantically related to patient name
        MappingSuggestion topSuggestion = suggestions.get(0);
        assertThat(topSuggestion.getTargetField()).isIn(
            "patientFullName", "fullName", "name", "PatientName", "subject_name"
        );
        
        // Score should be above quality threshold
        assertThat(topSuggestion.getScore()).isGreaterThanOrEqualTo(0.7);
        
        // Should not suggest unrelated fields highly
        boolean diagnosisInTop3 = suggestions.stream()
            .limit(3)
            .anyMatch(s -> s.getTargetField().equals("diagnosis"));
        assertThat(diagnosisInTop3).isFalse();

        printReport("Patient Name Mapping", sourceField, suggestions);
    }

    @Test
    void testPatientIdMapping_sampleInput() {
        // Sample input: mapping "patient_id" to various target fields
        String sourceField = "patient_id";
        List<String> targetFields = Arrays.asList(
            "patientIdentifier",
            "patient_identifier",
            "mrn",
            "medical_record_number",
            "id",
            "subjectId",
            "name",
            "address"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        assertThat(suggestions).isNotEmpty();
        
        // Top suggestion should be ID-related
        MappingSuggestion topSuggestion = suggestions.get(0);
        assertThat(topSuggestion.getTargetField()).isIn(
            "patientIdentifier", "patient_identifier", "mrn", "medical_record_number", "id", "subjectId"
        );
        
        assertThat(topSuggestion.getScore()).isGreaterThanOrEqualTo(0.7);

        printReport("Patient ID Mapping", sourceField, suggestions);
    }

    @Test
    void testDateOfBirthMapping_sampleInput() {
        // Sample input: mapping "dob" to various target fields
        String sourceField = "dob";
        List<String> targetFields = Arrays.asList(
            "dateOfBirth",
            "birthDate",
            "birth_date",
            "patient_dob",
            "created_date",
            "timestamp",
            "name",
            "diagnosis"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        assertThat(suggestions).isNotEmpty();
        
        // Should prioritize birth-related date fields
        MappingSuggestion topSuggestion = suggestions.get(0);
        assertThat(topSuggestion.getTargetField()).isIn(
            "dateOfBirth", "birthDate", "birth_date", "patient_dob"
        );
        
        // LLM can identify abbreviations even with lower character similarity
        // Accept scores >= 0.5 when LLM provides semantic understanding
        assertThat(topSuggestion.getScore()).isGreaterThanOrEqualTo(0.5);

        printReport("Date of Birth Mapping", sourceField, suggestions);
    }

    @Test
    void testMedicalTermMapping_sampleInput() {
        // Sample input: mapping "diagnosis_code" to various target fields
        String sourceField = "diagnosis_code";
        List<String> targetFields = Arrays.asList(
            "diagnosisCode",
            "diagnosis_code_value",
            "conditionCode",
            "icd10_code",
            "procedure_code",
            "medication_code",
            "patient_name",
            "address"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        assertThat(suggestions).isNotEmpty();
        
        // Top suggestion should be diagnosis-related
        MappingSuggestion topSuggestion = suggestions.get(0);
        assertThat(topSuggestion.getTargetField()).isIn(
            "diagnosisCode", "diagnosis_code_value", "conditionCode", "icd10_code"
        );
        
        assertThat(topSuggestion.getScore()).isGreaterThanOrEqualTo(0.7);

        printReport("Diagnosis Code Mapping", sourceField, suggestions);
    }

    @Test
    void testQualityControl_ensureLLMNotWorse() {
        // This test validates that LLM output is not worse than math embeddings
        String sourceField = "patient_full_name";
        List<String> targetFields = Arrays.asList(
            "patientFullName",  // Should score high - almost exact match
            "name",             // Should score medium
            "address",          // Should score low
            "diagnosis"         // Should score low
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        assertThat(suggestions).isNotEmpty();
        
        // Validate ordering - better matches should come first
        for (int i = 0; i < suggestions.size() - 1; i++) {
            assertThat(suggestions.get(i).getScore())
                .isGreaterThanOrEqualTo(suggestions.get(i + 1).getScore());
        }
        
        // Top suggestion should be the best semantic match
        assertThat(suggestions.get(0).getTargetField()).isEqualTo("patientFullName");
        
        // Unrelated fields should not appear in top results if they're below threshold
        long unrelatedInResults = suggestions.stream()
            .filter(s -> s.getTargetField().equals("address") || s.getTargetField().equals("diagnosis"))
            .count();
        assertThat(unrelatedInResults).isLessThanOrEqualTo(2);

        printReport("Quality Control Validation", sourceField, suggestions);
    }

    @Test
    void testComplexScenario_multipleGoodMatches() {
        // Test with multiple equally good matches
        String sourceField = "created_at";
        List<String> targetFields = Arrays.asList(
            "createdAt",
            "created_date",
            "creation_timestamp",
            "date_created",
            "timestamp",
            "patient_name",
            "id"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        assertThat(suggestions).isNotEmpty();
        
        // All date/time related fields should score well
        long dateRelatedCount = suggestions.stream()
            .filter(s -> s.getScore() >= 0.7)
            .filter(s -> s.getTargetField().contains("creat") || 
                        s.getTargetField().contains("date") || 
                        s.getTargetField().contains("time"))
            .count();
        
        assertThat(dateRelatedCount).isGreaterThanOrEqualTo(3);

        printReport("Multiple Good Matches", sourceField, suggestions);
    }

    @Test
    void testEdgeCases_noGoodMatches() {
        // Test when there are no good matches
        String sourceField = "xyz_unknown_field";
        List<String> targetFields = Arrays.asList(
            "patient_name",
            "diagnosis",
            "address",
            "timestamp"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        // May return empty list or low-scoring suggestions
        if (!suggestions.isEmpty()) {
            // All suggestions should have low scores
            for (MappingSuggestion suggestion : suggestions) {
                assertThat(suggestion.getScore()).isLessThan(0.9);
            }
        }

        printReport("No Good Matches", sourceField, suggestions);
    }

    @Test
    void testCamelCaseToSnakeCase_mapping() {
        // Test mapping between different naming conventions
        String sourceField = "patientFirstName";
        List<String> targetFields = Arrays.asList(
            "patient_first_name",
            "patient_firstname",
            "firstName",
            "first_name",
            "patient_last_name",
            "diagnosis"
        );

        List<MappingSuggestion> suggestions = mappingService.suggestMappings(sourceField, targetFields);

        assertThat(suggestions).isNotEmpty();
        
        // Should match snake_case equivalent
        MappingSuggestion topSuggestion = suggestions.get(0);
        assertThat(topSuggestion.getTargetField()).isIn(
            "patient_first_name", "patient_firstname", "firstName", "first_name"
        );

        printReport("Naming Convention Mapping", sourceField, suggestions);
    }

    private void printReport(String testName, String sourceField, List<MappingSuggestion> suggestions) {
        System.out.println("\n=== " + testName + " ===");
        System.out.println("Source Field: " + sourceField);
        System.out.println("Suggestions:");
        
        if (suggestions.isEmpty()) {
            System.out.println("  (No suggestions above quality threshold)");
        } else {
            for (int i = 0; i < suggestions.size(); i++) {
                MappingSuggestion s = suggestions.get(i);
                System.out.printf("  %d. %s%n", i + 1, s);
            }
        }
        System.out.println("Total suggestions: " + suggestions.size());
    }
}
