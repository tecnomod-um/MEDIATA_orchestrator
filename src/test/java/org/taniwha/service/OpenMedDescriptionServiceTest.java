package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenMedDescriptionService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OpenMedDescriptionService – label extraction and response parsing")
class OpenMedDescriptionServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedDescriptionServiceTest.class);

    @Mock LLMTextGenerator llmTextGenerator;

    private OpenMedDescriptionService service;

    @BeforeEach
    void setUp() {
        service = new OpenMedDescriptionService();
        ReflectionTestUtils.setField(service, "openmedUrl", "http://localhost:8002");
        ReflectionTestUtils.setField(service, "enabled",     true);
        ReflectionTestUtils.setField(service, "timeoutMs",   5000);
    }

    // ====================================================================
    // extractLabel – new "label | code" format
    // ====================================================================

    @Test
    @DisplayName("extractLabel: 'label | code' returns the label part")
    void extractLabel_newFormat_returnsLabel() {
        String result = OpenMedDescriptionService.extractLabel("Diabetes mellitus | 73211009");
        assertThat(result).isEqualTo("Diabetes mellitus");
        logger.info("[OpenMedDescTest] extractLabel new format: '{}'", result);
    }

    @Test
    @DisplayName("extractLabel: bare code returns the code as-is")
    void extractLabel_bareCode_returnsCode() {
        assertThat(OpenMedDescriptionService.extractLabel("73211009")).isEqualTo("73211009");
    }

    @Test
    @DisplayName("extractLabel: legacy 'code|label' returns the label part")
    void extractLabel_legacyFormat_returnsLabel() {
        assertThat(OpenMedDescriptionService.extractLabel("73211009|Diabetes mellitus"))
                .isEqualTo("Diabetes mellitus");
    }

    @Test
    @DisplayName("extractLabel: empty/null returns empty string")
    void extractLabel_empty_returnsEmpty() {
        assertThat(OpenMedDescriptionService.extractLabel("")).isEmpty();
        assertThat(OpenMedDescriptionService.extractLabel(null)).isEmpty();
    }

    @Test
    @DisplayName("extractLabel: plain label (no pipe) returns the label itself")
    void extractLabel_plainLabel_returnsLabel() {
        assertThat(OpenMedDescriptionService.extractLabel("Hypertension")).isEqualTo("Hypertension");
    }

    // ====================================================================
    // Service level – disabled / null inputs
    // ====================================================================

    @Test
    @DisplayName("disabled service returns empty map immediately")
    void disabled_returnsEmptyMap() {
        ReflectionTestUtils.setField(service, "enabled", false);
        DescriptionService.ColumnEnrichmentInput input =
                new DescriptionService.ColumnEnrichmentInput(
                        "Diagnosis", "Diagnosis | 46317288002",
                        List.of(new DescriptionService.ValueSpec("Hypertension", null, null)));
        assertThat(service.describeColumns(List.of(input))).isEmpty();
        logger.info("[OpenMedDescTest] disabled → empty map ✓");
    }

    @Test
    @DisplayName("null/empty input returns empty map")
    void nullInput_returnsEmptyMap() {
        assertThat(service.describeColumns(null)).isEmpty();
        assertThat(service.describeColumns(List.of())).isEmpty();
    }

    // ====================================================================
    // DescriptionService integration – OpenMed used when available
    // ====================================================================

    @Test
    @DisplayName("DescriptionService uses OpenMed when it returns results")
    void descriptionService_usesOpenMed_whenAvailable() {
        // LLM check is not reached when OpenMed returns results - no stub needed
        OpenMedDescriptionService openMedMock = new OpenMedDescriptionService() {
            @Override
            public Map<String, DescriptionService.EnrichmentResult> describeColumns(
                    List<DescriptionService.ColumnEnrichmentInput> inputs) {
                return Map.of("Diagnosis",
                        new DescriptionService.EnrichmentResult(
                                "Diagnosis column (SNOMED-based).",
                                Map.of("Hypertension", "Elevated arterial blood pressure.")));
            }
        };

        DescriptionService descSvc = new DescriptionService(
                llmTextGenerator, Executors.newSingleThreadExecutor(), openMedMock);

        DescriptionService.ColumnEnrichmentInput input =
                new DescriptionService.ColumnEnrichmentInput(
                        "Diagnosis", "Diagnosis | 46317288002",
                        List.of(new DescriptionService.ValueSpec("Hypertension", null, null)));

        Map<String, DescriptionService.EnrichmentResult> result =
                descSvc.generateEnrichmentBatchAsync(List.of(input)).join();

        assertThat(result).containsKey("Diagnosis");
        assertThat(result.get("Diagnosis").colDesc()).isEqualTo("Diagnosis column (SNOMED-based).");
        assertThat(result.get("Diagnosis").valueDescByValue())
                .containsEntry("Hypertension", "Elevated arterial blood pressure.");
        logger.info("[OpenMedDescTest] DescriptionService used OpenMed: '{}' ✓",
                result.get("Diagnosis").colDesc());
    }

    @Test
    @DisplayName("DescriptionService falls back when OpenMed returns empty")
    void descriptionService_fallsBackWhenOpenMedEmpty() {
        when(llmTextGenerator.isEnabled()).thenReturn(false);

        OpenMedDescriptionService emptyOpenMed = new OpenMedDescriptionService() {
            @Override
            public Map<String, DescriptionService.EnrichmentResult> describeColumns(
                    List<DescriptionService.ColumnEnrichmentInput> inputs) {
                return Map.of();
            }
        };

        DescriptionService descSvc = new DescriptionService(
                llmTextGenerator, Executors.newSingleThreadExecutor(), emptyOpenMed);

        DescriptionService.ColumnEnrichmentInput input =
                new DescriptionService.ColumnEnrichmentInput("Diagnosis", "", List.of());

        Map<String, DescriptionService.EnrichmentResult> result =
                descSvc.generateEnrichmentBatchAsync(List.of(input)).join();

        assertThat(result).containsKey("Diagnosis");
        assertThat(result.get("Diagnosis").colDesc()).isNotBlank();
        logger.info("[OpenMedDescTest] fallback → description='{}' ✓",
                result.get("Diagnosis").colDesc());
    }
}

