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
import java.util.Locale;
import java.util.Map;
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

    private EmbeddedColumn numericCol(String nodeId, String fileName, String column,
                                      String concept, double min, double max) {
        ColStats stats = new ColStats();
        stats.setHasIntegerMarker(true);
        stats.setNumMin(min);
        stats.setNumMax(max);

        return new EmbeddedColumn(
                nodeId, fileName, column, concept,
                Arrays.asList("integer", "min:" + min, "max:" + max),
                SAME_VEC,
                null,
                stats
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

    @Test
    @DisplayName("Prefix abbreviations must alias to full categorical values")
    void prefixAbbreviations_aliasToFullCategoricalValues() {
        lenient().when(embeddingService.embedSingleValue("hem")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        lenient().when(embeddingService.embedSingleValue("hemorrhagic")).thenReturn(new float[]{1.0f, 0.0f, 0.0f});
        lenient().when(embeddingService.embedSingleValue("isch")).thenReturn(new float[]{0.0f, 1.0f, 0.0f});
        lenient().when(embeddingService.embedSingleValue("ischemic")).thenReturn(new float[]{0.0f, 1.0f, 0.0f});

        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "stroke_registry.csv", "Etiology", "etiology", "Isch", "Hem", "HEM"),
                col("node2", "rehab.csv", "Type", "type", "Ischemic", "Hemorrhagic")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("etiology", "string", null, sources);
        Set<String> names = result.stream()
                .map(SuggestedValueDTO::getName)
                .collect(Collectors.toSet());

        assertEquals(2, result.size(), "Expected ischemic and hemorrhagic buckets only: " + names);
        assertTrue(names.contains("ischemic") || names.contains("isch"), "Expected ischemic/isch bucket: " + names);
        assertTrue(names.contains("hemorrhagic") || names.contains("hem"), "Expected hemorrhagic/hem bucket: " + names);

        for (SuggestedValueDTO vd : result) {
            Set<String> fileKeys = vd.getMapping().stream()
                    .map(r -> r.getNodeId() + "::" + r.getFileName())
                    .collect(Collectors.toSet());
            assertTrue(fileKeys.size() >= 2,
                    "Bucket '" + vd.getName() + "' should join the abbreviated and full-value files");
        }
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

    @Test
    @DisplayName("Clustering fallback must not merge distinct values from the same source column")
    void singleFile_manyDistinctValues_clusteringFallback_keepsSourceValuesSeparate() {
        java.util.ArrayList<String> times = new java.util.ArrayList<>();
        for (int i = 0; i < 26; i++) {
            times.add(String.format(Locale.ROOT, "08:%02d", i));
        }

        List<EmbeddedColumn> sources = List.of(
                col("node1", "assessments.csv", "assessment_time", "assessment time",
                        times.toArray(String[]::new))
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("assessment_time", "string", null, sources);

        assertEquals(26, result.size(),
                "Embedding fallback should not fold distinct values from one column into one bucket");

        for (SuggestedValueDTO bucket : result) {
            Set<String> distinctValues = bucket.getMapping().stream()
                    .map(ref -> String.valueOf(ref.getValue()))
                    .collect(Collectors.toSet());
            assertEquals(1, distinctValues.size(),
                    "Bucket '" + bucket.getName() + "' merged distinct source values: " + distinctValues);
        }
    }

    @Test
    @DisplayName("Numeric range fallback must keep all sources when one ordinal scale is unknown")
    void numericOrdinalCrosswalk_unknownScale_fallsBackWithAllSources() {
        List<EmbeddedColumn> sources = List.of(
                numericCol("node1", "sample_1.csv", "barthel_total", "barthel total", 10, 30),
                numericCol("node2", "sample_2.csv", "barthel_total", "barthel total", 10, 30),
                numericCol("node3", "sample_3.csv", "barthel_total", "barthel total", 5, 30)
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("barthel_total", "integer", null, sources);

        assertEquals(1, result.size(), "Unknown ordinal scales should fall back to one range bucket");
        assertEquals("integer", result.get(0).getName());
        assertEquals(3, result.get(0).getMapping().size(), "Fallback range must not drop any source column");
    }

    @Test
    @DisplayName("Generic observed integer ranges must not be rank-crosswalked as known scales")
    void numericOrdinalCrosswalk_allGenericRanges_fallsBackToRange() {
        List<EmbeddedColumn> sources = List.of(
                numericCol("node1", "sample_1.csv", "fim_total", "fim total", 6, 19),
                numericCol("node2", "sample_2.csv", "fim_total", "fim total", 3, 14),
                numericCol("node3", "sample_3.csv", "fim_total", "fim total", 6, 19)
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("fim_total", "integer", null, sources);

        assertEquals(1, result.size(),
                "Observed ranges that merely happen to be compact must not invent ordinal crosswalk buckets");
        assertEquals("integer", result.get(0).getName());
        assertEquals(3, result.get(0).getMapping().size(), "Fallback range must keep every source column");
    }

    @Test
    @DisplayName("Named clinical scales should crosswalk exact integer categories")
    void numericOrdinalCrosswalk_namedClinicalScales_emitExactBuckets() {
        assertExactScaleBuckets(
                "glasgow_coma_scale",
                List.of(
                        numericCol("node1", "trauma.csv", "Glasgow Coma Scale", "glasgow coma scale", 3, 15),
                        numericCol("node2", "icu.csv", "GCS Total", "gcs total", 3, 15)
                ),
                3,
                15
        );

        assertExactScaleBuckets(
                "nihss",
                List.of(
                        numericCol("node1", "stroke_registry.csv", "NIHSS Score", "nihss score", 0, 42),
                        numericCol("node2", "neurology.csv", "NIH Stroke Scale", "nih stroke scale", 0, 42)
                ),
                0,
                42
        );

        assertExactScaleBuckets(
                "mrs",
                List.of(
                        numericCol("node1", "stroke_registry.csv", "Modified Rankin Scale", "modified rankin scale", 0, 6),
                        numericCol("node2", "followup.csv", "mRS", "mrs", 0, 6)
                ),
                0,
                6
        );
    }

    @Test
    @DisplayName("Pain NRS 0-10 must not be treated as a Barthel 0/5/10 item")
    void numericOrdinalCrosswalk_painNrs_zeroToTen_usesEveryIntegerBucket() {
        List<SuggestedValueDTO> result = builder.buildValuesForConcept(
                "pain_score",
                "integer",
                null,
                List.of(
                        numericCol("node1", "nursing.csv", "Pain Score", "pain score", 0, 10),
                        numericCol("node2", "clinic.csv", "NRS Pain", "numeric rating scale pain", 0, 10)
                )
        );

        Set<String> names = result.stream().map(SuggestedValueDTO::getName).collect(Collectors.toSet());

        assertEquals(11, result.size(), "Pain NRS should emit 0..10 buckets, not Barthel-like 0/5/10");
        assertTrue(names.contains("1"), "Pain bucket 1 should exist: " + names);
        assertTrue(names.contains("4"), "Pain bucket 4 should exist: " + names);
        assertTrue(names.contains("9"), "Pain bucket 9 should exist: " + names);
        assertExactBucket(result, "0", 2, 0, 0);
        assertExactBucket(result, "10", 2, 10, 10);
    }

    @Test
    @DisplayName("ICU severity aliases should crosswalk within scale but not across different scales")
    void numericOrdinalCrosswalk_icuScales_handleAliasesAndBlockMixedInstruments() {
        assertExactScaleBuckets(
                "sofa",
                List.of(
                        numericCol("node1", "icu_a.csv", "SOFA Score", "sofa score", 0, 24),
                        numericCol("node2", "icu_b.csv", "Sequential Organ Failure Assessment", "sofa", 0, 24)
                ),
                0,
                24
        );

        assertExactScaleBuckets(
                "apache_ii",
                List.of(
                        numericCol("node1", "icu_a.csv", "APACHE II Score", "apache ii score", 0, 71),
                        numericCol("node2", "icu_b.csv", "Apache 2 total", "apache 2 total", 0, 71)
                ),
                0,
                71
        );

        List<SuggestedValueDTO> mixed = builder.buildValuesForConcept(
                "critical_illness_score",
                "integer",
                null,
                List.of(
                        numericCol("node1", "icu_a.csv", "APACHE II Score", "apache ii score", 0, 71),
                        numericCol("node2", "icu_b.csv", "SOFA Score", "sofa score", 0, 24)
                )
        );

        assertEquals(25, mixed.size(),
                "Once columns are in the same mapping, the value builder should crosswalk them even if instruments differ");
        assertBucketRefCount(mixed, "0", 2);
        assertBucketRefCount(mixed, "24", 2);
    }

    @Test
    @DisplayName("Mental health screening indexes should crosswalk within scale only")
    void numericOrdinalCrosswalk_mentalHealthScreeners() {
        assertExactScaleBuckets(
                "phq9",
                List.of(
                        numericCol("node1", "primary_care.csv", "PHQ-9 Total", "phq 9", 0, 27),
                        numericCol("node2", "behavioral_health.csv", "Patient Health Questionnaire 9", "patient health questionnaire 9", 0, 27)
                ),
                0,
                27
        );

        assertExactScaleBuckets(
                "gad7",
                List.of(
                        numericCol("node1", "primary_care.csv", "GAD-7 Score", "gad 7", 0, 21),
                        numericCol("node2", "behavioral_health.csv", "Generalized Anxiety Disorder 7", "generalized anxiety disorder 7", 0, 21)
                ),
                0,
                21
        );

        List<SuggestedValueDTO> mixed = builder.buildValuesForConcept(
                "mental_health_score",
                "integer",
                null,
                List.of(
                        numericCol("node1", "primary_care.csv", "PHQ-9 Total", "phq 9", 0, 27),
                        numericCol("node2", "behavioral_health.csv", "GAD-7 Score", "gad 7", 0, 21)
                )
        );

        assertEquals(22, mixed.size(), "Manually grouped PHQ-9 and GAD-7 should receive a proportional crosswalk");
        assertBucketRefCount(mixed, "0", 2);
        assertBucketRefCount(mixed, "21", 2);
    }

    @Test
    @DisplayName("Cognitive screening indexes with the same range must remain distinct")
    void numericOrdinalCrosswalk_cognitiveScales_sameRangeButDifferentInstrument() {
        assertExactScaleBuckets(
                "moca",
                List.of(
                        numericCol("node1", "memory_clinic.csv", "MoCA Score", "moca", 0, 30),
                        numericCol("node2", "geriatrics.csv", "Montreal Cognitive Assessment", "montreal cognitive assessment", 0, 30)
                ),
                0,
                30
        );

        assertExactScaleBuckets(
                "mmse",
                List.of(
                        numericCol("node1", "memory_clinic.csv", "MMSE", "mmse", 0, 30),
                        numericCol("node2", "geriatrics.csv", "Mini Mental State Examination", "mini mental state examination", 0, 30)
                ),
                0,
                30
        );

        List<SuggestedValueDTO> mixed = builder.buildValuesForConcept(
                "cognitive_score",
                "integer",
                null,
                List.of(
                        numericCol("node1", "memory_clinic.csv", "MoCA Score", "moca", 0, 30),
                        numericCol("node2", "geriatrics.csv", "MMSE", "mmse", 0, 30)
                )
        );

        assertEquals(31, mixed.size(), "Manually grouped MoCA and MMSE share a 0..30 domain and should crosswalk exactly");
        assertExactBucket(mixed, "0", 2, 0, 0);
        assertExactBucket(mixed, "30", 2, 30, 30);
    }

    @Test
    @DisplayName("Nursing, cardiovascular, neonatal, and sepsis indexes should use their own domains")
    void numericOrdinalCrosswalk_broadClinicalIndexes() {
        assertExactScaleBuckets(
                "braden",
                List.of(
                        numericCol("node1", "wound_care.csv", "Braden Scale", "braden", 6, 23),
                        numericCol("node2", "nursing.csv", "Braden Risk Score", "braden risk score", 6, 23)
                ),
                6,
                23
        );

        assertExactScaleBuckets(
                "morse_fall",
                List.of(
                        numericCol("node1", "falls.csv", "Morse Fall Scale", "morse fall scale", 0, 125),
                        numericCol("node2", "nursing.csv", "Morse Fall Risk", "morse fall", 0, 125)
                ),
                0,
                125,
                5
        );

        assertExactScaleBuckets(
                "cha2ds2_vasc",
                List.of(
                        numericCol("node1", "cardiology.csv", "CHA2DS2-VASc Score", "cha2ds2 vasc", 0, 9),
                        numericCol("node2", "afib_registry.csv", "CHA2DS2VASc", "cha2ds2vasc", 0, 9)
                ),
                0,
                9
        );

        assertExactScaleBuckets(
                "has_bled",
                List.of(
                        numericCol("node1", "cardiology.csv", "HAS-BLED Score", "has bled", 0, 9),
                        numericCol("node2", "anticoag.csv", "HASBLED", "hasbled", 0, 9)
                ),
                0,
                9
        );

        assertExactScaleBuckets(
                "qsofa",
                List.of(
                        numericCol("node1", "ed.csv", "qSOFA", "qsofa", 0, 3),
                        numericCol("node2", "sepsis.csv", "Quick SOFA Score", "quick sofa", 0, 3)
                ),
                0,
                3
        );

        assertExactScaleBuckets(
                "apgar",
                List.of(
                        numericCol("node1", "birth.csv", "Apgar Score", "apgar", 0, 10),
                        numericCol("node2", "neonatal.csv", "APGAR total", "apgar total", 0, 10)
                ),
                0,
                10
        );

        List<SuggestedValueDTO> sameRangeDifferentIndexes = builder.buildValuesForConcept(
                "cardiology_risk",
                "integer",
                null,
                List.of(
                        numericCol("node1", "afib_registry.csv", "CHA2DS2VASc", "cha2ds2vasc", 0, 9),
                        numericCol("node2", "anticoag.csv", "HASBLED", "hasbled", 0, 9)
                )
        );

        assertEquals(10, sameRangeDifferentIndexes.size(),
                "Manually grouped CHA2DS2-VASc and HAS-BLED share 0..9 and should crosswalk exactly");
        assertExactBucket(sameRangeDifferentIndexes, "0", 2, 0, 0);
        assertExactBucket(sameRangeDifferentIndexes, "9", 2, 9, 9);
    }

    @Test
    @DisplayName("Single-source categorical fallback must not merge Male and Female by embedding")
    void singleSourceCategoricalFallback_keepsMaleFemaleSeparate() {
        lenient().when(embeddingService.embedSingleValue("male")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("female")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("unknown")).thenReturn(SAME_VEC);

        List<EmbeddedColumn> sources = List.of(
                col("node1", "sample_dataset_2_elements.csv", "gender", "gender", "Unknown", "Male", "Female")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("gender", "string", null, sources);
        Set<String> names = result.stream().map(SuggestedValueDTO::getName).collect(Collectors.toSet());

        assertTrue(names.contains("male"), "Male must remain its own bucket: " + names);
        assertTrue(names.contains("female"), "Female must remain its own bucket: " + names);
        assertTrue(names.contains("unknown"), "Unknown must remain its own bucket: " + names);
        assertEquals(3, result.size(), "Expected one bucket per source category");
    }

    @Test
    @DisplayName("Categorical fallback must not merge Yes and No by embedding")
    void categoricalFallback_keepsYesNoSeparate() {
        lenient().when(embeddingService.embedSingleValue("yes")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("no")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("partial")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("recovered")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("deceased")).thenReturn(SAME_VEC);
        lenient().when(embeddingService.embedSingleValue("improved")).thenReturn(SAME_VEC);

        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "sample_dataset_2_elements.csv", "outcome_status", "outcome status", "No", "Partial", "Yes"),
                col("node2", "sample_dataset_1_elements.csv", "outcome", "outcome", "Recovered", "Deceased", "Improved")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("outcome", "string", null, sources);
        Set<String> names = result.stream().map(SuggestedValueDTO::getName).collect(Collectors.toSet());

        assertTrue(names.contains("yes"), "Yes must remain its own bucket: " + names);
        assertTrue(names.contains("no"), "No must remain its own bucket: " + names);
        assertFalse(result.stream().anyMatch(v -> v.getMapping().stream()
                        .map(r -> String.valueOf(r.getValue()).toLowerCase())
                        .collect(Collectors.toSet())
                        .containsAll(Set.of("yes", "no"))),
                "No bucket may contain both Yes and No");
    }

    @Test
    @DisplayName("Closed-domain categorical harmonization must not merge different values from the same source column")
    void closedDomainCategorical_keepsSameColumnCategoriesSeparate() {
        lenient().when(embeddingService.embedSingleValue(anyString())).thenReturn(SAME_VEC);

        List<EmbeddedColumn> sources = Arrays.asList(
                col("node1", "education_a.csv", "education", "education", "High school", "Middle school", "Master degree"),
                col("node2", "education_b.csv", "education", "education", "High school", "Middle school", "Masters degree")
        );

        List<SuggestedValueDTO> result = builder.buildValuesForConcept("education", "string", null, sources);

        assertFalse(result.stream().anyMatch(v -> v.getMapping().stream()
                        .map(r -> String.valueOf(r.getValue()).toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet())
                        .containsAll(Set.of("high school", "middle school"))),
                "One education bucket must not contain both High school and Middle school from the same column");
    }

    private void assertExactScaleBuckets(String unionKey, List<EmbeddedColumn> sources, int min, int max) {
        assertExactScaleBuckets(unionKey, sources, min, max, 1);
    }

    private void assertExactScaleBuckets(String unionKey, List<EmbeddedColumn> sources, int min, int max, int step) {
        List<SuggestedValueDTO> result = builder.buildValuesForConcept(unionKey, "integer", null, sources);
        Set<String> names = result.stream().map(SuggestedValueDTO::getName).collect(Collectors.toSet());

        int expectedBuckets = ((max - min) / step) + 1;
        assertEquals(expectedBuckets, result.size(),
                unionKey + " should emit one bucket per expected score step: " + names);
        assertTrue(names.contains(String.valueOf(min)), "Missing minimum bucket for " + unionKey + ": " + names);
        assertTrue(names.contains(String.valueOf(max)), "Missing maximum bucket for " + unionKey + ": " + names);
        assertExactBucket(result, String.valueOf(min), sources.size(), min, Math.min(max, min + step - 1));
        assertExactBucket(result, String.valueOf(max), sources.size(), max, max);
    }

    private void assertExactBucket(List<SuggestedValueDTO> result, String name, int expectedRefs, int min, int max) {
        SuggestedValueDTO bucket = result.stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing bucket " + name));

        assertEquals(expectedRefs, bucket.getMapping().size(), "Unexpected ref count for bucket " + name);
        for (var ref : bucket.getMapping()) {
            assertTrue(ref.getValue() instanceof Map<?, ?>, "Expected numeric interval map for bucket " + name);
            Map<?, ?> interval = (Map<?, ?>) ref.getValue();
            assertEquals((double) min, ((Number) interval.get("minValue")).doubleValue(), 0.0001);
            assertEquals((double) max, ((Number) interval.get("maxValue")).doubleValue(), 0.0001);
            assertEquals("integer", interval.get("type"));
        }
    }

    private void assertBucketRefCount(List<SuggestedValueDTO> result, String name, int expectedRefs) {
        SuggestedValueDTO bucket = result.stream()
                .filter(v -> name.equals(v.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing bucket " + name));

        assertEquals(expectedRefs, bucket.getMapping().size(), "Unexpected ref count for bucket " + name);
        for (var ref : bucket.getMapping()) {
            assertTrue(ref.getValue() instanceof Map<?, ?>, "Expected numeric interval map for bucket " + name);
            assertEquals("integer", ((Map<?, ?>) ref.getValue()).get("type"));
        }
    }
}
