package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.model.ColumnRecord;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the OpenMed terminology pipeline.
 *
 * <h3>Contract under test</h3>
 * <ul>
 *   <li>{@link OpenMedTerminologyService#inferBatch} returns <em>validated search phrases</em>
 *       only – no Snowstorm lookups are performed inside it.  The terminology field in
 *       a mapping DTO is populated by {@code TerminologyLookupService} (owned by
 *       {@code MappingEnrichmentHelper}) after {@code inferBatch} returns.</li>
 *   <li>{@link OpenMedTerminologyService#resolveWithSnowstorm} returns a valid SNOMED
 *       code+label (e.g. {@code "73211009|Diabetes mellitus"}) when Snowstorm finds a
 *       match, or {@code ""} when it does not.  The raw OpenMed search phrase is
 *       <em>never</em> returned as a terminology value.</li>
 *   <li>{@link OpenMedTerminologyService#isValidTerm} rejects numeric-only, single-char,
 *       and punctuation-only tokens produced by the NER model.</li>
 * </ul>
 *
 * <p>Snowstorm is mocked in every case.  Embedding functionality is intentionally
 * excluded from this class.  {@code @Timeout} annotations prevent hangs if the
 * OpenMed Python service is unavailable.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenMed Terminology Service – pipeline tests")
class OpenMedTerminologyServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedTerminologyServiceTest.class);

    @Mock
    private RDFService rdfService;

    private OpenMedTerminologyService service;

    @BeforeEach
    void setUp() {
        service = new OpenMedTerminologyService(rdfService);
        ReflectionTestUtils.setField(service, "openmedUrl",           "http://localhost:8002");
        ReflectionTestUtils.setField(service, "enabled",              true);
        ReflectionTestUtils.setField(service, "configuredBatchSize",  5);
        ReflectionTestUtils.setField(service, "timeoutMs",            5000);
        ReflectionTestUtils.setField(service, "snowstormEnabled",     true);
        logger.info("[OpenMedTest] setUp – service ready");
    }

    // ====================================================================
    // 1. isValidTerm – term quality gate
    // ====================================================================

    @Test
    @DisplayName("isValidTerm: accepts well-formed medical phrases")
    void isValidTerm_acceptsValidMedicalTerms() {
        assertThat(service.isValidTerm("hypertension")).isTrue();
        assertThat(service.isValidTerm("diabetes mellitus")).isTrue();
        assertThat(service.isValidTerm("blood pressure")).isTrue();
        assertThat(service.isValidTerm("Stroke")).isTrue();
        assertThat(service.isValidTerm("ab")).isTrue();
        logger.info("[OpenMedTest] isValidTerm accepts valid medical terms ✓");
    }

    @Test
    @DisplayName("isValidTerm: rejects null and blank strings")
    void isValidTerm_rejectsNullAndBlank() {
        assertThat(service.isValidTerm(null)).isFalse();
        assertThat(service.isValidTerm("")).isFalse();
        assertThat(service.isValidTerm("   ")).isFalse();
        logger.info("[OpenMedTest] isValidTerm rejects null/blank ✓");
    }

    @Test
    @DisplayName("isValidTerm: rejects single-character tokens")
    void isValidTerm_rejectsSingleCharacter() {
        assertThat(service.isValidTerm("a")).isFalse();
        assertThat(service.isValidTerm("X")).isFalse();
        logger.info("[OpenMedTest] isValidTerm rejects single-char ✓");
    }

    @Test
    @DisplayName("isValidTerm: rejects purely numeric tokens (age, count, score, etc.)")
    void isValidTerm_rejectsPurelyNumericTokens() {
        assertThat(service.isValidTerm("40")).isFalse();
        assertThat(service.isValidTerm("123")).isFalse();
        assertThat(service.isValidTerm("0")).isFalse();
        logger.info("[OpenMedTest] isValidTerm rejects numeric-only tokens ✓");
    }

    @Test
    @DisplayName("isValidTerm: rejects punctuation/symbol-only tokens (NER artefacts)")
    void isValidTerm_rejectsPunctuationOnlyTokens() {
        assertThat(service.isValidTerm("--")).isFalse();
        assertThat(service.isValidTerm("__")).isFalse();
        assertThat(service.isValidTerm("a1")).isFalse();   // only 1 alphabetic char
        logger.info("[OpenMedTest] isValidTerm rejects punctuation tokens ✓");
    }

    // ====================================================================
    // 2. resolveWithSnowstorm – must return SNOMED code or ""
    // ====================================================================

    @Test
    @DisplayName("resolveWithSnowstorm: returns SNOMED code when Snowstorm finds a match")
    void resolveWithSnowstorm_returnsSnowstormCode_whenFound() {
        OntologyTermDTO dto = new OntologyTermDTO(
                "1", "Diabetes mellitus", "", "http://snomed.info/sct/73211009");
        when(rdfService.getSNOMEDTermSuggestions("diabetes")).thenReturn(List.of(dto));

        String result = service.resolveWithSnowstorm("diabetes", "diabetes column");

        assertThat(result).isEqualTo("73211009|Diabetes mellitus");
        logger.info("[OpenMedTest] Snowstorm code returned: '{}'", result);
    }

    @Test
    @DisplayName("resolveWithSnowstorm: returns EMPTY STRING when Snowstorm finds nothing")
    void resolveWithSnowstorm_returnsEmpty_whenSnowstormFindsNothing() {
        when(rdfService.getSNOMEDTermSuggestions(anyString())).thenReturn(List.of());

        String result = service.resolveWithSnowstorm("hypertension", "blood pressure column");

        // The raw OpenMed phrase MUST NOT be returned – only SNOMED or "".
        assertThat(result).isEmpty();
        assertThat(result).doesNotContain("hypertension");
        logger.info("[OpenMedTest] Snowstorm empty → '' returned (not the search phrase) ✓");
    }

    @Test
    @DisplayName("resolveWithSnowstorm: returns EMPTY STRING when Snowstorm throws")
    void resolveWithSnowstorm_returnsEmpty_whenSnowstormThrows() {
        when(rdfService.getSNOMEDTermSuggestions(anyString()))
                .thenThrow(new RuntimeException("Snowstorm unavailable"));

        String result = service.resolveWithSnowstorm("stroke", "stroke etiology");

        assertThat(result).isEmpty();
        logger.info("[OpenMedTest] Snowstorm threw → '' returned ✓");
    }

    @Test
    @DisplayName("resolveWithSnowstorm: returns EMPTY STRING when Snowstorm is disabled")
    void resolveWithSnowstorm_returnsEmpty_whenSnowstormDisabled() {
        ReflectionTestUtils.setField(service, "snowstormEnabled", false);

        String result = service.resolveWithSnowstorm("hypertension", "blood pressure");

        // Even with a valid search phrase, if Snowstorm is disabled we cannot
        // produce a valid SNOMED code, so we must return "".
        assertThat(result).isEmpty();
        verifyNoInteractions(rdfService);
        logger.info("[OpenMedTest] Snowstorm disabled → '' returned (no raw phrase) ✓");
    }

    @Test
    @DisplayName("resolveWithSnowstorm: returns EMPTY STRING when both inputs fail validation")
    void resolveWithSnowstorm_returnsEmpty_whenBothInputsInvalid() {
        String result = service.resolveWithSnowstorm("40", "123");

        assertThat(result).isEmpty();
        verifyNoInteractions(rdfService);
        logger.info("[OpenMedTest] both invalid → '' returned ✓");
    }

    @Test
    @DisplayName("resolveWithSnowstorm: uses fallback text when OpenMed term is invalid, still needs SNOMED")
    void resolveWithSnowstorm_usesFallbackText_thenRequiresSnowstorm() {
        // "40" is a numeric-only artefact; fallback to "diagnosis" which Snowstorm doesn't know either.
        when(rdfService.getSNOMEDTermSuggestions("diagnosis")).thenReturn(List.of());

        String result = service.resolveWithSnowstorm("40", "diagnosis");

        // Snowstorm found nothing for "diagnosis" too → must return "".
        assertThat(result).isEmpty();
        verify(rdfService).getSNOMEDTermSuggestions("diagnosis");
        logger.info("[OpenMedTest] fallback text used but Snowstorm empty → '' ✓");
    }

    @Test
    @DisplayName("resolveWithSnowstorm: uses fallback text and returns SNOMED when Snowstorm matches")
    void resolveWithSnowstorm_usesFallbackText_returnsSnomed_whenSnowstormMatches() {
        OntologyTermDTO dto = new OntologyTermDTO(
                "1", "Diagnosis", "", "http://snomed.info/sct/439401001");
        when(rdfService.getSNOMEDTermSuggestions("diagnosis")).thenReturn(List.of(dto));

        String result = service.resolveWithSnowstorm("40", "diagnosis");

        assertThat(result).isEqualTo("439401001|Diagnosis");
        verify(rdfService).getSNOMEDTermSuggestions("diagnosis");
        logger.info("[OpenMedTest] fallback text resolved to SNOMED: '{}'", result);
    }

    @Test
    @DisplayName("resolveWithSnowstorm: uses the first Snowstorm result when multiple are returned")
    void resolveWithSnowstorm_usesFirstSnowstormResult() {
        OntologyTermDTO first  = new OntologyTermDTO("1", "Hypertension",           "", "http://snomed.info/sct/38341003");
        OntologyTermDTO second = new OntologyTermDTO("2", "Essential hypertension", "", "http://snomed.info/sct/59621000");
        when(rdfService.getSNOMEDTermSuggestions("hypertension"))
                .thenReturn(List.of(first, second));

        String result = service.resolveWithSnowstorm("hypertension", "blood pressure");

        assertThat(result).isEqualTo("38341003|Hypertension");
        logger.info("[OpenMedTest] first-of-multiple Snowstorm result: '{}'", result);
    }

    // ====================================================================
    // 3. inferBatch – returns validated search phrases, NOT SNOMED codes
    // ====================================================================

    @Test
    @DisplayName("inferBatch: returns empty list when service is disabled")
    void inferBatch_disabled_returnsEmpty() {
        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.inferBatch(List.of(
                new ColumnRecord("Diagnosis", List.of("Hypertension"))))).isEmpty();
        verifyNoInteractions(rdfService);
    }

    @Test
    @DisplayName("inferBatch: returns empty list for null/empty input")
    void inferBatch_nullOrEmptyInput_returnsEmpty() {
        assertThat(service.inferBatch(null)).isEmpty();
        assertThat(service.inferBatch(List.of())).isEmpty();
        verifyNoInteractions(rdfService);
    }

    @Test
    @DisplayName("batchSize() returns configured value, minimum 1")
    void batchSize_returnsConfiguredValueClampedToOne() {
        assertThat(service.batchSize()).isEqualTo(5);
        ReflectionTestUtils.setField(service, "configuredBatchSize", -3);
        assertThat(service.batchSize()).isEqualTo(1);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("inferBatch: does NOT call Snowstorm (Snowstorm is called by TerminologyLookupService later)")
    void inferBatch_doesNotCallSnowstorm() {
        // Even if the service is reachable, inferBatch must never touch Snowstorm.
        List<ColumnRecord> columns = List.of(
                new ColumnRecord("Diagnosis", List.of("Hypertension", "Diabetes")));

        service.inferBatch(columns);

        // rdfService.getSNOMEDTermSuggestions must not have been called.
        verifyNoInteractions(rdfService);
        logger.info("[OpenMedTest] inferBatch did not call Snowstorm ✓");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("inferBatch: returned search terms are valid phrases (not SNOMED codes, not bare integers)")
    void inferBatch_returnedTerms_areValidSearchPhrases() {
        List<ColumnRecord> columns = List.of(
                new ColumnRecord("Diagnosis",    List.of("Hypertension", "Diabetes Mellitus")),
                new ColumnRecord("PatientAge",   List.of("45", "67")),      // numeric values
                new ColumnRecord("BloodPressure", List.of("120/80"))        // mixed value
        );

        long t0 = System.currentTimeMillis();
        List<OpenMedTerminologyService.InferredTerm> results = service.inferBatch(columns);
        long elapsed = System.currentTimeMillis() - t0;

        logger.info("[OpenMedTest] inferBatch returned {} term(s) in {}ms", results.size(), elapsed);

        for (OpenMedTerminologyService.InferredTerm t : results) {
            String colTerm = t.colSearchTerm();
            logger.info("[OpenMedTest]   col='{}' searchTerm='{}'", t.colKey(), colTerm);

            // The search phrase must never look like a SNOMED code or a bare number.
            assertThat(colTerm)
                    .as("colSearchTerm for col='%s' must not be a bare integer", t.colKey())
                    .doesNotMatch("^\\d+$");
            assertThat(colTerm)
                    .as("colSearchTerm for col='%s' must not be a SNOMED code+label", t.colKey())
                    .doesNotMatch("^\\d{6,}\\|.+");

            // The returned phrase must pass isValidTerm (it was validated before returning).
            assertThat(service.isValidTerm(colTerm))
                    .as("colSearchTerm '%s' for col='%s' must be a valid search phrase", colTerm, t.colKey())
                    .isTrue();

            // Value terms (if any) must also be valid search phrases, never SNOMED codes.
            if (t.valueSearchTerms() != null) {
                for (Map.Entry<String, String> e : t.valueSearchTerms().entrySet()) {
                    String valTerm = e.getValue();
                    logger.info("[OpenMedTest]     val='{}' → '{}'", e.getKey(), valTerm);

                    assertThat(valTerm)
                            .as("value term for '%s' must not be a bare integer", e.getKey())
                            .doesNotMatch("^\\d+$");
                    assertThat(valTerm)
                            .as("value term for '%s' must not be a SNOMED code+label", e.getKey())
                            .doesNotMatch("^\\d{6,}\\|.+");
                    assertThat(service.isValidTerm(valTerm))
                            .as("value term '%s' must pass isValidTerm", valTerm)
                            .isTrue();
                }
            }
        }

        // Snowstorm must not have been touched by inferBatch.
        verifyNoInteractions(rdfService);
    }

    // ====================================================================
    // 4. End-to-end correctness: the full SNOMED pipeline
    //    inferBatch → caller builds lookup keys → TerminologyLookupService
    //    → resolveTerminology → SNOMED code or ""
    //    Simulated here via resolveWithSnowstorm directly.
    // ====================================================================

    @Test
    @DisplayName("CORRECTNESS: SNOMED code returned for a recognised medical term")
    void correctness_snomedCodeReturnedForRecognisedTerm() {
        OntologyTermDTO dto = new OntologyTermDTO(
                "1", "Hypertension", "", "http://snomed.info/sct/38341003");
        when(rdfService.getSNOMEDTermSuggestions("hypertension")).thenReturn(List.of(dto));

        String result = service.resolveWithSnowstorm("hypertension", "blood pressure column");

        assertThat(result).isEqualTo("38341003|Hypertension");
        assertThat(result).matches("^\\d{6,}\\|.+");   // must be proper SNOMED format
        logger.info("[OpenMedTest] SNOMED code for recognised term: '{}'", result);
    }

    @Test
    @DisplayName("CORRECTNESS: empty string returned (not the phrase) when Snowstorm has no match")
    void correctness_emptyReturnedWhenSnowstormHasNoMatch() {
        when(rdfService.getSNOMEDTermSuggestions(anyString())).thenReturn(List.of());

        String result = service.resolveWithSnowstorm("synucleinopathy", "diagnosis");

        // Must be "" – the terminology field must never contain a raw search phrase.
        assertThat(result).isEmpty();
        logger.info("[OpenMedTest] CORRECTNESS: no SNOMED match → '' (not raw phrase) ✓");
    }

    @Test
    @DisplayName("CORRECTNESS: numeric column values never produce a terminology entry")
    void correctness_numericValuesNeverProduceTerminology() {
        // Numeric values (age, score, count) are invalid as search terms.
        // resolveWithSnowstorm must return "" without querying Snowstorm.
        String forAge   = service.resolveWithSnowstorm("40",  "age");
        String forScore = service.resolveWithSnowstorm("123", "score");

        assertThat(forAge).isEmpty();
        assertThat(forScore).isEmpty();

        // "age" and "score" are valid fallback texts, but since Snowstorm is mocked
        // with default (no stubbing), getSNOMEDTermSuggestions should have been called
        // for the fallback terms.
        verify(rdfService, atLeastOnce()).getSNOMEDTermSuggestions(anyString());
        logger.info("[OpenMedTest] numeric values → '' ✓");
    }

    // ====================================================================
    // 5. Graceful degradation
    // ====================================================================

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("inferBatch: gracefully handles an unreachable OpenMed service")
    void inferBatch_unreachableService_returnsEmptyQuickly() {
        ReflectionTestUtils.setField(service, "openmedUrl", "http://localhost:19999");
        ReflectionTestUtils.setField(service, "timeoutMs",  2000);

        long t0 = System.currentTimeMillis();
        List<OpenMedTerminologyService.InferredTerm> result = service.inferBatch(
                List.of(new ColumnRecord("Diagnosis", List.of("Hypertension"))));
        long elapsed = System.currentTimeMillis() - t0;

        assertThat(result).isEmpty();
        assertThat(elapsed).as("must not hang").isLessThan(9_000L);
        verifyNoInteractions(rdfService);
        logger.info("[OpenMedTest] unreachable service: {}ms, result={}", elapsed, result.size());
    }
}
