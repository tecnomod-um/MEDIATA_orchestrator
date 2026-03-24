package org.taniwha.service.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.service.DescriptionService;
import org.taniwha.service.OpenMedTerminologyService;
import org.taniwha.service.OpenMedTerminologyService.InferredTerm;
import org.taniwha.service.TerminologyLookupService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies that the terminology output of the full OpenMed pipeline is correct:
 * valid SNOMED CT codes in the same shape Snowstorm returns them
 * (i.e. matching {@code ^\d{6,}(\|.+)?$}), or empty.
 *
 * <h3>Pipeline under test</h3>
 * <pre>
 *   OpenMedTerminologyService.inferBatch()
 *       → MappingEnrichmentHelper builds lookup keys ("colKey|searchTerm")
 *       → TerminologyLookupService.batchLookupTerminology()   [Snowstorm]
 *       → MappingEnrichmentHelper.resolveTerminology()        [isSnowstormCode filter]
 *       → SuggestedMappingDTO / SuggestedValueDTO .terminology field
 * </pre>
 *
 * <p>All external services (OpenMed HTTP, Snowstorm) are mocked so the test is
 * self-contained and fast.  Embedding functionality is excluded.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenMed → Snowstorm → output: terminology must be valid SNOMED CT or empty")
class MappingEnrichmentHelperTerminologyTest {

    private static final Logger logger =
            LoggerFactory.getLogger(MappingEnrichmentHelperTerminologyTest.class);

    /** Pattern that matches the Snowstorm output format after the format change.
     *  Preferred: {@code "label | code"} (e.g. {@code "Diabetes mellitus | 73211009"}).
     *  Also accepts bare numeric code (no label) for robustness. */
    private static final Pattern SNOMED_FORMAT = Pattern.compile("^(.+ \\| )?\\d{6,}$");

    @Mock OpenMedTerminologyService openMedTerminologyService;
    @Mock TerminologyLookupService terminologyLookupService;
    @Mock DescriptionService descriptionService;

    private MappingEnrichmentHelper helper;

    @BeforeEach
    void setUp() {
        MappingServiceSettings settings = new MappingServiceSettings(
                100, 20, 0.33, 0.56, 6, 40, 10, 0.22, 4, 2, 2, 6);
        helper = new MappingEnrichmentHelper(
                openMedTerminologyService,
                terminologyLookupService, descriptionService, settings);

        // Safe defaults – individual tests override what they need.
        lenient().when(openMedTerminologyService.batchSize()).thenReturn(5);
        lenient().when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Collections.emptyMap());
        lenient().when(descriptionService.generateEnrichmentBatchAsync(any()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyMap()));
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /** Build a minimal mapping map for the given column key. */
    private static Map<String, SuggestedMappingDTO> mappingOf(
            String colKey, SuggestedMappingDTO mapping) {
        Map<String, SuggestedMappingDTO> m = new LinkedHashMap<>();
        m.put(colKey, mapping);
        return m;
    }

    /** Build a SuggestedMappingDTO with one group that contains named values. */
    private static SuggestedMappingDTO mappingWithValues(String... valueNames) {
        SuggestedGroupDTO group = new SuggestedGroupDTO();
        group.setValues(java.util.Arrays.stream(valueNames).map(n -> {
            SuggestedValueDTO v = new SuggestedValueDTO();
            v.setName(n);
            return v;
        }).toList());
        SuggestedMappingDTO dto = new SuggestedMappingDTO();
        dto.setGroups(List.of(group));
        return dto;
    }

    /** Returns true when the terminology string matches Snowstorm format or is empty. */
    private static boolean isSnowstormOrEmpty(String s) {
        return s == null || s.isEmpty() || SNOMED_FORMAT.matcher(s).matches();
    }

    // ====================================================================
    // 1. Column-level terminology output
    // ====================================================================

    @Test
    @DisplayName("CORRECT OUTPUT: valid SNOMED label+code is stored in the mapping DTO")
    void columnTerminology_validSnomedCodeWithLabel_storedAsIs() {
        // OpenMed suggests "diabetes mellitus" as search term for the "Diagnosis" column.
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "diabetes mellitus", Map.of())));
        // Snowstorm returns a proper SNOMED concept in the new "label | code" format.
        // The lookup key the helper builds is "Diagnosis|diabetes mellitus".
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Diagnosis|diabetes mellitus", "Diabetes mellitus | 73211009"));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        assertThat(mapping.getTerminology())
                .as("Valid SNOMED label+code must be stored as-is")
                .isEqualTo("Diabetes mellitus | 73211009");
        assertThat(mapping.getTerminology())
                .as("Terminology must match SNOMED format ^(.+ \\| )?\\d{6,}$")
                .matches(SNOMED_FORMAT.pattern());
        logger.info("[TermTest] column SNOMED label+code: '{}'", mapping.getTerminology());
    }

    @Test
    @DisplayName("CORRECT OUTPUT: valid SNOMED code without label is stored in the mapping DTO")
    void columnTerminology_validSnomedCodeWithoutLabel_storedAsIs() {
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "diabetes mellitus", Map.of())));
        // Snowstorm returns numeric-only concept (valid – label is optional).
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Diagnosis|diabetes mellitus", "73211009"));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        assertThat(mapping.getTerminology())
                .as("Numeric-only SNOMED concept ID must be stored as-is")
                .isEqualTo("73211009");
        assertThat(mapping.getTerminology())
                .as("Terminology must match SNOMED format ^\\d{6,}$")
                .matches("^\\d{6,}$");
        logger.info("[TermTest] column SNOMED no-label: '{}'", mapping.getTerminology());
    }

    @Test
    @DisplayName("CORRECT OUTPUT: synthetic CONCEPT_ fallback is filtered – terminology is empty")
    void columnTerminology_syntheticConceptFallback_filteredToEmpty() {
        // TerminologyLookupService can emit CONCEPT_XXXXXXXXX when Snowstorm returns nothing
        // AND terminology.fallback.enabled=true.  That synthetic code is NOT valid SNOMED CT.
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "rare syndrome", Map.of())));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Diagnosis|rare syndrome", "CONCEPT_000123456"));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        assertThat(mapping.getTerminology())
                .as("CONCEPT_ fallback must never appear in the terminology field")
                .doesNotContain("CONCEPT_");
        assertThat(mapping.getTerminology())
                .as("Filtered synthetic code must produce empty terminology")
                .isEmpty();
        logger.info("[TermTest] CONCEPT_ filtered → terminology='{}'", mapping.getTerminology());
    }

    @Test
    @DisplayName("CORRECT OUTPUT: raw search phrase is filtered – terminology is empty")
    void columnTerminology_rawSearchPhrase_filteredToEmpty() {
        // Snowstorm could (in error) echo the search phrase back; it must be rejected.
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "hypertension", Map.of())));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Diagnosis|hypertension", "hypertension"));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        assertThat(mapping.getTerminology())
                .as("Raw search phrase must never be stored as terminology")
                .doesNotMatch("[a-zA-Z ]+");       // not a bare word
        assertThat(mapping.getTerminology())
                .isEmpty();
        logger.info("[TermTest] raw phrase filtered → terminology='{}'", mapping.getTerminology());
    }

    @Test
    @DisplayName("CORRECT OUTPUT: empty Snowstorm result → terminology is empty string (not null)")
    void columnTerminology_emptySnowstormResult_givesEmptyString() {
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "diabetes mellitus", Map.of())));
        // batchLookupTerminology returns empty map → no result for our key.

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        assertThat(mapping.getTerminology())
                .as("Null terminology must be normalised to empty string")
                .isNotNull();
        assertThat(mapping.getTerminology()).isEmpty();
        logger.info("[TermTest] empty Snowstorm → terminology='{}'", mapping.getTerminology());
    }

    // ====================================================================
    // 2. Value-level terminology output
    // ====================================================================

    @Test
    @DisplayName("CORRECT OUTPUT: valid SNOMED label+code stored in the value DTO")
    void valueTerminology_validSnomedCodeWithLabel_storedAsIs() {
        // OpenMed: column "Diagnosis", value "Hypertension" → search term "hypertension"
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "diabetes mellitus",
                        Map.of("Hypertension", "hypertension"))));
        // Snowstorm lookup key for the value is "Diagnosis|hypertension"
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of(
                        "Diagnosis|diabetes mellitus", "Diabetes mellitus | 73211009",
                        "Diagnosis|hypertension",      "Hypertension | 38341003"));

        SuggestedMappingDTO mapping = mappingWithValues("Hypertension");
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        SuggestedValueDTO hyper = mapping.getGroups().get(0).getValues().get(0);
        assertThat(hyper.getTerminology())
                .as("Value terminology must be the Snowstorm SNOMED label+code")
                .isEqualTo("Hypertension | 38341003");
        assertThat(hyper.getTerminology()).matches(SNOMED_FORMAT.pattern());
        logger.info("[TermTest] value SNOMED label+code: '{}'", hyper.getTerminology());
    }

    @Test
    @DisplayName("CORRECT OUTPUT: synthetic CONCEPT_ in value DTO is filtered to empty")
    void valueTerminology_syntheticConceptFallback_filteredToEmpty() {
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "diabetes mellitus",
                        Map.of("RareSyndrome", "rare syndrome"))));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of(
                        "Diagnosis|diabetes mellitus", "Diabetes mellitus | 73211009",
                        "Diagnosis|rare syndrome",     "CONCEPT_999000001"));

        SuggestedMappingDTO mapping = mappingWithValues("RareSyndrome");
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        SuggestedValueDTO rare = mapping.getGroups().get(0).getValues().get(0);
        assertThat(rare.getTerminology())
                .as("CONCEPT_ fallback must be filtered from value terminology")
                .doesNotContain("CONCEPT_");
        assertThat(rare.getTerminology()).isEmpty();
        logger.info("[TermTest] value CONCEPT_ filtered → terminology='{}'", rare.getTerminology());
    }

    @Test
    @DisplayName("CORRECT OUTPUT: raw phrase in value DTO is filtered to empty")
    void valueTerminology_rawSearchPhrase_filteredToEmpty() {
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Diagnosis", "diabetes mellitus",
                        Map.of("Stroke", "stroke"))));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of(
                        "Diagnosis|diabetes mellitus", "Diabetes mellitus | 73211009",
                        "Diagnosis|stroke",            "stroke"));    // raw phrase echoed back

        SuggestedMappingDTO mapping = mappingWithValues("Stroke");
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Diagnosis", mapping)), null);

        SuggestedValueDTO stroke = mapping.getGroups().get(0).getValues().get(0);
        assertThat(stroke.getTerminology())
                .as("Raw phrase must never be stored as value terminology")
                .isEmpty();
        logger.info("[TermTest] value raw phrase filtered → terminology='{}'", stroke.getTerminology());
    }

    // ====================================================================
    // 3. Mixed-batch: multiple columns, multiple values, mixed Snowstorm results
    // ====================================================================

    @Test
    @DisplayName("CORRECT OUTPUT: mixed batch – only valid SNOMED codes appear in terminology fields")
    void mixedBatch_onlySnowstormCodesInTerminology() {
        // Three columns; Snowstorm returns: one SNOMED code, one CONCEPT_ fallback, one empty.
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(
                        new InferredTerm("Diagnosis",    "diabetes mellitus",
                                Map.of("Hypertension", "hypertension",
                                       "Stroke",        "stroke")),
                        new InferredTerm("PatientAge",   "patient age",   Map.of()),
                        new InferredTerm("MedicalScore", "medical score", Map.of())
                ));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of(
                        "Diagnosis|diabetes mellitus",  "Diabetes mellitus | 73211009", // ✓ valid
                        "Diagnosis|hypertension",        "Hypertension | 38341003",      // ✓ valid
                        "Diagnosis|stroke",              "CONCEPT_000230690",            // ✗ synthetic
                        "PatientAge|patient age",        "Age | 397669002",              // ✓ valid
                        "MedicalScore|medical score",    "medical score"                 // ✗ raw phrase
                ));

        SuggestedMappingDTO diagMapping  = mappingWithValues("Hypertension", "Stroke");
        SuggestedMappingDTO ageMapping   = new SuggestedMappingDTO();
        SuggestedMappingDTO scoreMapping = new SuggestedMappingDTO();

        List<Map<String, SuggestedMappingDTO>> allMappings = List.of(
                mappingOf("Diagnosis",    diagMapping),
                mappingOf("PatientAge",   ageMapping),
                mappingOf("MedicalScore", scoreMapping)
        );

        helper.populateTerminologyAndDescriptionsBatch(allMappings, null);

        // ── column terminology ────────────────────────────────────────────
        assertThat(diagMapping.getTerminology())
                .as("Diagnosis: valid SNOMED must be stored")
                .isEqualTo("Diabetes mellitus | 73211009");

        assertThat(ageMapping.getTerminology())
                .as("PatientAge: valid SNOMED must be stored")
                .isEqualTo("Age | 397669002");

        assertThat(scoreMapping.getTerminology())
                .as("MedicalScore: raw phrase must be filtered to empty")
                .isEmpty();

        // ── value terminology ─────────────────────────────────────────────
        List<SuggestedValueDTO> vals = diagMapping.getGroups().get(0).getValues();
        SuggestedValueDTO hyper  = vals.stream().filter(v -> "Hypertension".equals(v.getName())).findFirst().orElseThrow();
        SuggestedValueDTO stroke = vals.stream().filter(v -> "Stroke".equals(v.getName())).findFirst().orElseThrow();

        assertThat(hyper.getTerminology())
                .as("Hypertension value: valid SNOMED must be stored")
                .isEqualTo("Hypertension | 38341003");

        assertThat(stroke.getTerminology())
                .as("Stroke value: CONCEPT_ must be filtered to empty")
                .isEmpty();

        // ── invariant: every terminology field is valid SNOMED or empty ───
        String[] allTerminologies = {
                diagMapping.getTerminology(),
                ageMapping.getTerminology(),
                scoreMapping.getTerminology(),
                hyper.getTerminology(),
                stroke.getTerminology()
        };
        for (String t : allTerminologies) {
            assertThat(isSnowstormOrEmpty(t))
                    .as("Every terminology field must be valid SNOMED or empty; got: '%s'", t)
                    .isTrue();
        }

        logger.info("[TermTest] mixed batch results:");
        logger.info("[TermTest]   Diagnosis    col → '{}'", diagMapping.getTerminology());
        logger.info("[TermTest]   PatientAge   col → '{}'", ageMapping.getTerminology());
        logger.info("[TermTest]   MedicalScore col → '{}'", scoreMapping.getTerminology());
        logger.info("[TermTest]   Hypertension val → '{}'", hyper.getTerminology());
        logger.info("[TermTest]   Stroke       val → '{}'", stroke.getTerminology());
    }

    // ====================================================================
    // 4. Edge cases in the SNOMED format gate
    // ====================================================================

    @Test
    @DisplayName("FORMAT: 5-digit codes are too short to be SNOMED – filtered to empty")
    void formatGate_fiveDigitCode_filteredToEmpty() {
        // Real SNOMED concept IDs are at least 6 digits.  A 5-digit value must be rejected.
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Age", "age", Map.of())));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Age|age", "12345"));   // only 5 digits

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Age", mapping)), null);

        assertThat(mapping.getTerminology()).isEmpty();
        logger.info("[TermTest] 5-digit code filtered → '{}'", mapping.getTerminology());
    }

    @Test
    @DisplayName("FORMAT: 6-digit code is the minimum valid SNOMED concept ID")
    void formatGate_sixDigitCode_passes() {
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Age", "age", Map.of())));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Age|age", "Some concept | 123456"));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Age", mapping)), null);

        assertThat(mapping.getTerminology()).isEqualTo("Some concept | 123456");
        logger.info("[TermTest] 6-digit code passes → '{}'", mapping.getTerminology());
    }

    @Test
    @DisplayName("FORMAT: typical 8-digit SNOMED label+code is accepted")
    void formatGate_eightDigitCode_passes() {
        when(openMedTerminologyService.inferBatch(any()))
                .thenReturn(List.of(new InferredTerm("Stroke", "stroke", Map.of())));
        when(terminologyLookupService.batchLookupTerminology(any()))
                .thenReturn(Map.of("Stroke|stroke", "Stroke | 230690007"));

        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        helper.populateTerminologyAndDescriptionsBatch(
                List.of(mappingOf("Stroke", mapping)), null);

        assertThat(mapping.getTerminology()).isEqualTo("Stroke | 230690007");
        assertThat(mapping.getTerminology()).matches(SNOMED_FORMAT.pattern());
        logger.info("[TermTest] 8-digit code passes → '{}'", mapping.getTerminology());
    }
}
