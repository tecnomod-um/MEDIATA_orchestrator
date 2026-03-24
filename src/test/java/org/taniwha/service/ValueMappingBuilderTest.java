package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.model.ColStats;
import org.taniwha.model.EmbeddedColumn;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link ValueMappingBuilder} focusing on single-character value handling.
 *
 * <p>Background: single-character values such as "Y"/"N" produce embedding vectors with almost
 * no semantic signal.  Before the fix, two distinct values like "Y" and "N" could be collapsed
 * into a single bucket because their cosine similarity was above the clustering threshold.
 * These tests verify that the fix keeps distinct single-character values in separate buckets.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ValueMappingBuilder – single-character value tests")
class ValueMappingBuilderTest {

    @Mock
    private EmbeddingService embeddingService;

    private ValueMappingBuilder builder;

    /**
     * A single unit vector used to simulate pathologically similar embeddings for all
     * single-character tokens ("y", "n", "m", "f", etc.).  With cosine similarity == 1.0
     * the old code would merge every single-char value into one cluster; the fix must
     * prevent that.
     */
    private static final float[] SAME_VEC = {1.0f, 0.0f, 0.0f};

    /**
     * Orthogonal vectors for longer tokens so that the embedding-based aliasing path is
     * exercised for those tokens only.
     */
    private static final float[] VEC_MALE    = {0.0f, 1.0f, 0.0f};
    private static final float[] VEC_FEMALE  = {0.0f, 0.0f, 1.0f};
    private static final float[] VEC_OTHER   = {0.7f, 0.7f, 0.0f};
    private static final float[] VEC_SINGLE  = {0.0f, 0.7f, 0.7f};
    private static final float[] VEC_MARRIED = {0.7f, 0.0f, 0.7f};

    @BeforeEach
    void setUp() {
        builder = new ValueMappingBuilder(embeddingService);

        // Default: return the same vector for any single-char value (worst-case similarity = 1.0)
        lenient().when(embeddingService.embedSingleValue(anyString())).thenReturn(SAME_VEC);

        // Distinct vectors for longer tokens used in the demographics scenario
        lenient().when(embeddingService.embedSingleValue("male")).thenReturn(VEC_MALE);
        lenient().when(embeddingService.embedSingleValue("female")).thenReturn(VEC_FEMALE);
        lenient().when(embeddingService.embedSingleValue("other")).thenReturn(VEC_OTHER);
        lenient().when(embeddingService.embedSingleValue("single")).thenReturn(VEC_SINGLE);
        lenient().when(embeddingService.embedSingleValue("married")).thenReturn(VEC_MARRIED);
    }

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    private EmbeddedColumn col(String nodeId, String fileName, String column,
                               String concept, String... rawValues) {
        return new EmbeddedColumn(
                nodeId, fileName, column, concept,
                Arrays.asList(rawValues),
                SAME_VEC,   // column-level embedding – not relevant for value mapping
                null,
                new ColStats()
        );
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    /**
     * Y and N appearing across two distinct files must produce two separate value buckets,
     * not one.  This is the canonical regression test for the original bug.
     */
    @Test
    @DisplayName("Y/N across two files must produce two distinct buckets")
    void singleCharYN_twoFiles_produceTwoBuckets() {
        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "registry.csv",    "AF",    "af",    "Y", "N"),
                col("node2", "assessment.csv",  "AF",    "af",    "Y", "N")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("af", "string", null, sources);

        Set<String> names = result.stream()
                .map(SuggestedValueDTO::getName)
                .collect(Collectors.toSet());

        assertEquals(2, result.size(),
                "Expected exactly 2 buckets (Y and N), but got " + result.size() + ": " + names);
        assertTrue(names.contains("y"),
                "Expected a bucket for 'y' but found: " + names);
        assertTrue(names.contains("n"),
                "Expected a bucket for 'n' but found: " + names);
    }

    /**
     * When a column holds three single-character values (e.g. M, F, O for Sex) across
     * two files, all three must remain as distinct buckets.
     */
    @Test
    @DisplayName("Three single-char values (M/F/O) across two files must produce three distinct buckets")
    void singleCharMFO_twoFiles_produceThreeBuckets() {
        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "patients.csv", "Gender", "gender", "M", "F", "O"),
                col("node2", "intake.csv",   "Sex",    "sex",    "M", "F", "O")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("sex", "string", null, sources);

        Set<String> names = result.stream()
                .map(SuggestedValueDTO::getName)
                .collect(Collectors.toSet());

        assertEquals(3, result.size(),
                "Expected exactly 3 buckets (M, F, O), but got " + result.size() + ": " + names);
        assertTrue(names.contains("m"),
                "Expected bucket 'm' but found: " + names);
        assertTrue(names.contains("f"),
                "Expected bucket 'f' but found: " + names);
        assertTrue(names.contains("o"),
                "Expected bucket 'o' but found: " + names);
    }

    /**
     * When one file uses full words (Male/Female/Other) and another uses single-char
     * abbreviations (M/F/O), the isObviousAlias path must group them correctly into
     * three buckets (one per concept).
     */
    @Test
    @DisplayName("Single-char abbreviations (M/F/O) must alias to full words (Male/Female/Other)")
    void singleCharAbbreviations_aliasToFullWords() {
        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "hospital.csv", "Patient Gender", "patient gender", "Male", "Female", "Other"),
                col("node2", "clinic.csv",   "Sex",            "sex",            "M",    "F",      "O")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("sex", "string", null, sources);

        assertFalse(result.isEmpty(), "Expected at least one mapping bucket");

        // Each canonical token must cover refs from both files
        for (SuggestedValueDTO vd : result) {
            Set<String> fileKeys = vd.getMapping().stream()
                    .map(r -> r.getNodeId() + "::" + r.getFileName())
                    .collect(Collectors.toSet());
            assertTrue(fileKeys.size() >= 2,
                    "Bucket '" + vd.getName() + "' should reference at least 2 files but only references: " + fileKeys);
        }

        // Three distinct concepts, not one collapsed bucket
        assertTrue(result.size() >= 3,
                "Expected at least 3 buckets (Male, Female, Other) but got " + result.size() + ": "
                        + result.stream().map(SuggestedValueDTO::getName).collect(Collectors.toList()));
    }

    /**
     * Mixed domain: Y/N alongside a longer categorical value (FLUTTER).
     * All three must remain distinct buckets.
     */
    @Test
    @DisplayName("Y/N/FLUTTER across two files must produce three distinct buckets")
    void singleCharWithLongerValue_twoFiles_produceThreeBuckets() {
        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "cardiology.csv", "AF",     "af",  "Y", "N", "FLUTTER"),
                col("node2", "assessment.csv", "AF_YN",  "af",  "Y", "N", "FLUTTER")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("af", "string", null, sources);

        Set<String> names = result.stream()
                .map(SuggestedValueDTO::getName)
                .collect(Collectors.toSet());

        assertEquals(3, result.size(),
                "Expected exactly 3 buckets (Y, N, FLUTTER), but got " + result.size() + ": " + names);
        assertTrue(names.contains("y"),   "Expected bucket 'y' but found: " + names);
        assertTrue(names.contains("n"),   "Expected bucket 'n' but found: " + names);
        assertTrue(names.contains("flutter"), "Expected bucket 'flutter' but found: " + names);
    }

    /**
     * When only a single file is present (so closed-domain harmonization is skipped due
     * to the cross-file guard), the fallback clustered path must also keep distinct
     * single-char values in separate buckets.
     */
    @Test
    @DisplayName("Single file with Y/N must still produce two distinct buckets via clustering fallback")
    void singleFile_singleCharYN_clusteringFallback_twoBuckets() {
        // Only one file → joinsAcrossAtLeastTwoFiles returns false → buildClosedDomainCategorical
        // returns empty → falls through to buildClusteredValueMappings.
        List<EmbeddedColumn> sources = List.of(
                col("node1", "registry.csv", "AF", "af", "Y", "Y", "N", "N")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("af", "string", null, sources);

        Set<String> names = result.stream()
                .map(SuggestedValueDTO::getName)
                .collect(Collectors.toSet());

        assertEquals(2, result.size(),
                "Expected exactly 2 buckets (Y and N) even in the clustering fallback, but got "
                        + result.size() + ": " + names);
        assertTrue(names.contains("y"),
                "Expected a bucket for 'y' but found: " + names);
        assertTrue(names.contains("n"),
                "Expected a bucket for 'n' but found: " + names);
    }
}
