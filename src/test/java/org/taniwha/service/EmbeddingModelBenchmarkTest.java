package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.service.mapping.MappingService;
import org.taniwha.util.MappingMathUtil;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark comparing embedding models for medical column-name matching.
 *
 * <p>Tests each candidate model on 27 expected column-name pairs drawn from
 * real medical datasets (lab results, vital signs, clinical assessments,
 * demographics, medications, ICD-10 coding). Reports:
 *
 * <ul>
 *   <li><b>Mean Sim</b>  — average cosine similarity across the 27 expected pairs.
 *       Higher means the model understands that abbreviation ↔ full-name are semantically close.</li>
 *   <li><b>≥0.56</b>     — pairs scoring at or above the cluster threshold (0.56); these pairs
 *       can be merged by the abbreviation-aware algorithm even without Jaccard overlap.</li>
 *   <li><b>Grouped</b>   — pairs actually grouped together by MappingService end-to-end.</li>
 * </ul>
 *
 * <p>Models that cannot be downloaded within 150 s are skipped with a note.
 * The test always passes; it is informational / exploratory.
 *
 * <p>To switch the production embedding model, set in {@code application.properties}:
 * <pre>
 * spring.ai.embedding.transformer.onnx.model-uri=https://...
 * spring.ai.embedding.transformer.tokenizer.uri=https://...
 * </pre>
 */
public class EmbeddingModelBenchmarkTest {

    // ─── Model catalogue ──────────────────────────────────────────────────────

    record ModelSpec(String name, String modelUri, String tokenizerUri, String description) {}

    /**
     * Candidate models, ordered from fastest/smallest to best for medical domain.
     * A {@code null} modelUri/tokenizerUri means "use TransformersEmbeddingModel defaults"
     * (currently all-MiniLM-L6-v2 downloaded from GitHub/Spring AI).
     */
    private static final List<ModelSpec> CANDIDATES = List.of(

        new ModelSpec(
            "all-MiniLM-L6-v2",
            null,   // use TransformersEmbeddingModel defaults
            null,
            "BASELINE  — general-purpose sentence transformer, 384 dims, 22M params. "
            + "Current production default. Fast but not trained on medical text."
        ),

        new ModelSpec(
            "all-mpnet-base-v2",
            "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/onnx/model.onnx",
            "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/tokenizer.json",
            "GENERAL UPGRADE — MPNet-based, 768 dims, 110M params. "
            + "Best general sentence-transformer on SBERT benchmarks. No medical pretraining."
        ),

        new ModelSpec(
            "pubmedbert-base-embeddings",
            "https://huggingface.co/Dev-SN/pubmedbert-base-embeddings-onnx/resolve/main/onnx/model.onnx",
            "https://huggingface.co/Dev-SN/pubmedbert-base-embeddings-onnx/resolve/main/tokenizer.json",
            "MEDICAL  — PubMedBERT fine-tuned for biomedical semantic similarity, 768 dims. "
            + "Pre-trained on 21M PubMed abstracts; fine-tuned on clinical NLI + STS data. "
            + "Understands medical abbreviations (WBC, SBP, GCS, HbA1c, etc.) natively. "
            + "NOTE: NeuML repo has no ONNX export; using Dev-SN ONNX conversion (same weights)."
        )
    );

    // ─── Expected pairs (27 total, 6 categories) ──────────────────────────────

    record ExpectedPair(String colA, String colB, String category) {
        @Override public String toString() { return colA + " ↔ " + colB; }
    }

    private static final List<ExpectedPair> EXPECTED_PAIRS = List.of(
        // Lab results (6)
        new ExpectedPair("WBC",              "White Blood Cell Count",    "Lab"),
        new ExpectedPair("Cr",               "Serum Creatinine",          "Lab"),
        new ExpectedPair("eGFR",             "Glomerular Filtration Rate","Lab"),
        new ExpectedPair("LDL",              "Low Density Lipoprotein",   "Lab"),
        new ExpectedPair("HDL",              "High Density Lipoprotein",  "Lab"),
        new ExpectedPair("Total Cholesterol","Cholesterol Total",         "Lab"),
        // Vital signs (5)
        new ExpectedPair("SBP",              "Blood Pressure Systolic",   "Vitals"),
        new ExpectedPair("DBP",              "Blood Pressure Diastolic",  "Vitals"),
        new ExpectedPair("HR (bpm)",         "Heart Rate",                "Vitals"),
        new ExpectedPair("Temp (C)",         "Body Temperature",          "Vitals"),
        new ExpectedPair("RR (breaths/min)", "Respiratory Rate",          "Vitals"),
        // Clinical assessments (5)
        new ExpectedPair("NIHSS Score",      "NIH Stroke Scale",          "Clinical"),
        new ExpectedPair("mRS",              "Modified Rankin Scale",     "Clinical"),
        new ExpectedPair("GCS",              "Glasgow Coma Scale",        "Clinical"),
        new ExpectedPair("EF (%)",           "Ejection Fraction",         "Clinical"),
        new ExpectedPair("Pain Score",       "Pain Level (0-10)",         "Clinical"),
        // Demographics (5)
        new ExpectedPair("DOB",              "Date of Birth",             "Demo"),
        new ExpectedPair("Sex",              "Patient Gender",            "Demo"),
        new ExpectedPair("Marital",          "Marital Status",            "Demo"),
        new ExpectedPair("Ethnicity",        "Race/Ethnicity",            "Demo"),
        new ExpectedPair("Insurance Type",   "Primary Insurance",         "Demo"),
        // Medications (3)
        new ExpectedPair("Drug",             "Medication Name",           "Meds"),
        new ExpectedPair("Dosage",           "Dose",                      "Meds"),
        new ExpectedPair("Administration Route", "Route",                 "Meds"),
        // ICD-10 (3)
        new ExpectedPair("Diagnosis Code",   "Primary Diagnosis",         "ICD10"),
        new ExpectedPair("Comorbidities",    "Secondary Diagnosis",       "ICD10"),
        new ExpectedPair("HbA1c",            "Hemoglobin A1C",            "ICD10")
    );

    // ─── Fixture files ─────────────────────────────────────────────────────────

    private static final List<String> FIXTURE_PATHS = List.of(
        "src/test/resources/mapping/fixture-medical-lab-results.json",
        "src/test/resources/mapping/fixture-medical-vitals.json",
        "src/test/resources/mapping/fixture-medical-clinical-assessments.json",
        "src/test/resources/mapping/fixture-medical-patient-demographics.json",
        "src/test/resources/mapping/fixture-medical-medications.json",
        "src/test/resources/mapping/fixture-medical-icd.json"
    );

    // ─── Settings mirror of ModelComparisonTest ────────────────────────────────

    private static final MappingServiceSettings SETTINGS =
        new MappingServiceSettings(60, 120, 0.33, 0.56, 6, 40, 10, 0.22, 4, 3, 2, 6);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ─── Test entry point ──────────────────────────────────────────────────────

    @Test
    void benchmark_medical_embedding_models() throws Exception {
        System.out.println();
        System.out.println("═".repeat(80));
        System.out.println("   EMBEDDING MODEL BENCHMARK — MEDICAL TERMINOLOGY MAPPING");
        System.out.println("   " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("═".repeat(80));
        System.out.printf("%nTesting %d expected pairs across %d medical categories:%n", EXPECTED_PAIRS.size(), 6);
        Map<String, Long> byCat = new LinkedHashMap<>();
        for (ExpectedPair p : EXPECTED_PAIRS) byCat.merge(p.category(), 1L, Long::sum);
        byCat.forEach((cat, n) -> System.out.printf("  %-10s %d pairs%n", cat, n));

        List<ModelResult> results = new ArrayList<>();

        for (ModelSpec spec : CANDIDATES) {
            System.out.printf("%n── Testing %-36s ──%n", spec.name());
            System.out.println("   " + spec.description());

            ModelResult result = testModel(spec);
            results.add(result);
            printModelSummary(result);
        }

        printComparisonTable(results);
        printRecommendation(results);

        // The only hard assertions: at least one model must load, and must produce meaningful scores
        long loadedCount = results.stream().filter(ModelResult::loaded).count();
        assertTrue(loadedCount >= 1,
            "At least one embedding model must load and run successfully");

        results.stream().filter(ModelResult::loaded).forEach(r ->
            assertTrue(r.meanSim() > 0,
                "Loaded model " + r.name() + " must produce non-zero similarity scores"));
    }

    // ─── Per-model testing ─────────────────────────────────────────────────────

    private ModelResult testModel(ModelSpec spec) {
        TransformersEmbeddingModel onnxModel = null;
        ExecutorService loader = Executors.newSingleThreadExecutor();
        try {
            // Load model with a 150-second timeout to allow large downloads
            Future<TransformersEmbeddingModel> future = loader.submit(() -> {
                TransformersEmbeddingModel m = new TransformersEmbeddingModel();
                // null URI means "use TransformersEmbeddingModel defaults" (all-MiniLM-L6-v2)
                if (spec.modelUri() != null) m.setModelResource(spec.modelUri());
                if (spec.tokenizerUri() != null) m.setTokenizerResource(spec.tokenizerUri());
                m.afterPropertiesSet();
                return m;
            });
            onnxModel = future.get(150, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("   ⏱ SKIPPED — model load timed out after 150 s");
            System.out.println("   (The model file is large; run the test with internet access to try it.)");
            return ModelResult.skipped(spec.name(), "load timed out");
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            System.out.println("   ⚠ SKIPPED — could not load model: " + msg);
            return ModelResult.skipped(spec.name(), msg);
        } finally {
            loader.shutdown();
        }

        try {
            // 1. Embedding-level similarity for every expected pair
            EmbeddingsClient client = new EmbeddingsClient(onnxModel);
            int dim = client.embed("test").length;

            List<Double> sims = new ArrayList<>();
            long aboveThreshold = 0;
            for (ExpectedPair pair : EXPECTED_PAIRS) {
                float[] a = client.embed(pair.colA());
                float[] b = client.embed(pair.colB());
                double sim = MappingMathUtil.cosine(a, b);
                sims.add(sim);
                if (sim >= SETTINGS.columnClusterThreshold()) aboveThreshold++;
            }
            double meanSim = sims.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            // 2. End-to-end MappingService grouping test
            MappingService mappingService = buildMappingService(onnxModel);
            int groupedCount = countCorrectlyGrouped(mappingService);

            return new ModelResult(spec.name(), true, null, dim, meanSim, aboveThreshold, groupedCount, sims);
        } catch (Exception e) {
            System.out.println("   ✗ Error during scoring: " + e.getMessage());
            return ModelResult.skipped(spec.name(), "scoring error: " + e.getMessage());
        }
    }

    // ─── MappingService factory (mirrors ModelComparisonTest.TestConfig) ───────

    private MappingService buildMappingService(TransformersEmbeddingModel onnxModel) {
        EmbeddingsClient client = new EmbeddingsClient(onnxModel);
        EmbeddingService embeddingService = new EmbeddingService(client);

        TerminologyLookupService terminologyService =
            Mockito.mock(TerminologyLookupService.class);
        Mockito.when(terminologyService.batchLookupTerminology(Mockito.any()))
            .thenReturn(Collections.emptyMap());

        TerminologyTermInferenceService inferenceService =
            Mockito.mock(TerminologyTermInferenceService.class);
        Mockito.when(inferenceService.batchSize()).thenReturn(5);
        Mockito.when(inferenceService.inferBatch(Mockito.any()))
            .thenReturn(Collections.emptyList());

        LLMTextGenerator llm = Mockito.mock(LLMTextGenerator.class);
        Mockito.when(llm.isEnabled()).thenReturn(false);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        DescriptionService descriptionService = new DescriptionService(llm, executor);

        ValueMappingBuilder valueMappingBuilder = new ValueMappingBuilder(embeddingService);

        org.taniwha.service.OpenMedTerminologyService openMedService =
            Mockito.mock(org.taniwha.service.OpenMedTerminologyService.class);
        Mockito.when(openMedService.batchSize()).thenReturn(5);
        Mockito.when(openMedService.inferBatch(Mockito.any()))
            .thenReturn(Collections.emptyList());

        return new MappingService(
            embeddingService, terminologyService, inferenceService,
            openMedService, descriptionService, valueMappingBuilder, OBJECT_MAPPER, SETTINGS
        );
    }

    // ─── Count correctly grouped pairs across all fixtures ─────────────────────

    private int countCorrectlyGrouped(MappingService service) throws Exception {
        int count = 0;
        for (String fixturePath : FIXTURE_PATHS) {
            Path p = Paths.get(fixturePath);
            if (!Files.exists(p)) continue;
            MappingSuggestRequestDTO req;
            try (InputStream is = Files.newInputStream(p)) {
                req = OBJECT_MAPPER.readValue(is, MappingSuggestRequestDTO.class);
            }
            List<Map<String, SuggestedMappingDTO>> result = service.suggestMappings(req);
            for (ExpectedPair pair : EXPECTED_PAIRS) {
                if (areGroupedTogether(result, pair.colA(), pair.colB())) count++;
            }
        }
        return count;
    }

    private static boolean areGroupedTogether(
            List<Map<String, SuggestedMappingDTO>> result, String colA, String colB) {
        for (Map<String, SuggestedMappingDTO> m : result) {
            List<String> cols = m.values().iterator().next().getColumns();
            if (cols != null && cols.contains(colA) && cols.contains(colB)) return true;
        }
        return false;
    }

    // ─── Reporting ─────────────────────────────────────────────────────────────

    private static void printModelSummary(ModelResult r) {
        if (!r.loaded()) {
            System.out.printf("   Status: SKIPPED (%s)%n", r.skipReason());
            return;
        }
        System.out.printf("   Embedding dims : %d%n", r.dims());
        System.out.printf("   Mean pair sim  : %.4f%n", r.meanSim());
        System.out.printf("   Pairs ≥ 0.56   : %d / %d%n", r.aboveThreshold(), EXPECTED_PAIRS.size());
        System.out.printf("   Correctly grouped: %d / %d%n", r.grouped(), EXPECTED_PAIRS.size());
        System.out.printf("   Per-category pair similarities:%n");
        Map<String, List<Double>> byCat = new LinkedHashMap<>();
        for (int i = 0; i < EXPECTED_PAIRS.size(); i++) {
            ExpectedPair p = EXPECTED_PAIRS.get(i);
            byCat.computeIfAbsent(p.category(), k -> new ArrayList<>()).add(r.sims().get(i));
        }
        byCat.forEach((cat, sims) -> {
            double avg = sims.stream().mapToDouble(d -> d).average().orElse(0);
            System.out.printf("     %-12s avg=%.3f  %s%n", cat, avg,
                sims.stream().mapToDouble(d -> d).mapToObj(d -> String.format("%.3f", d))
                    .reduce((a, b2) -> a + " " + b2).orElse(""));
        });
    }

    private static void printComparisonTable(List<ModelResult> results) {
        System.out.println();
        System.out.println("─".repeat(80));
        System.out.println("  COMPARISON TABLE");
        System.out.println("─".repeat(80));
        System.out.printf("  %-34s %5s  %8s  %8s  %10s%n",
            "Model", "Dims", "MeanSim", "≥0.56", "Grouped/" + EXPECTED_PAIRS.size());
        System.out.println("  " + "─".repeat(76));
        for (ModelResult r : results) {
            if (r.loaded()) {
                String marker = r.name().contains("MiniLM") ? " [BASELINE]" : "";
                System.out.printf("  %-34s %5d  %8.4f  %8d  %10d%n",
                    r.name() + marker, r.dims(), r.meanSim(), r.aboveThreshold(), r.grouped());
            } else {
                System.out.printf("  %-34s %5s  %8s  %8s  %10s%n",
                    r.name(), "-", "SKIPPED", "-", "-");
            }
        }
        System.out.println("─".repeat(80));
    }

    private static void printRecommendation(List<ModelResult> results) {
        System.out.println();
        System.out.println("═".repeat(80));
        System.out.println("  RECOMMENDATION");
        System.out.println("═".repeat(80));

        ModelResult best = results.stream()
            .filter(ModelResult::loaded)
            .max(Comparator.<ModelResult>comparingInt(ModelResult::grouped)
                .thenComparingDouble(ModelResult::meanSim))
            .orElse(null);

        ModelResult baseline = results.stream()
            .filter(r -> r.name().equals("all-MiniLM-L6-v2") && r.loaded())
            .findFirst()
            .or(() -> results.stream().filter(ModelResult::loaded).findFirst())
            .orElse(null);

        if (best == null || baseline == null) {
            System.out.println("  Insufficient data — only the baseline model was tested.");
            System.out.println("  Run with full internet access to compare all models.");
        } else if (best.name().equals("all-MiniLM-L6-v2")) {
            System.out.println("  ✓ Current model (all-MiniLM-L6-v2) is the best among those tested.");
            System.out.println("  Consider testing NeuML/pubmedbert-base-embeddings if not yet available.");
        } else {
            System.out.printf("  ★  Winner: %s%n%n", best.name());
            int gain = best.grouped() - baseline.grouped();
            System.out.printf("  Correctly groups %d/%d expected pairs (+%d vs baseline)%n",
                best.grouped(), EXPECTED_PAIRS.size(), gain);
            System.out.printf("  Mean similarity: %.4f vs %.4f baseline (+%.1f%%)%n%n",
                best.meanSim(), baseline.meanSim(),
                100.0 * (best.meanSim() - baseline.meanSim()) / baseline.meanSim());

            System.out.println("  WHY this model is best for the medical domain:");
            if (best.name().contains("pubmed") || best.name().contains("biomed")) {
                System.out.println("  • Pre-trained on 21M+ PubMed abstracts → natively understands biomedical vocabulary");
                System.out.println("  • Knows that WBC = White Blood Cell Count, SBP = systolic blood pressure, etc.");
                System.out.println("  • Fine-tuned on clinical NLI + STS data → calibrated for semantic similarity");
                System.out.println("  • 768-dim vectors capture richer semantic nuances than 384-dim MiniLM");
            } else if (best.name().contains("mpnet")) {
                System.out.println("  • MPNet architecture provides better sentence-level representations than MiniLM");
                System.out.println("  • 768-dim vectors capture more semantic detail");
                System.out.println("  • Best general sentence transformer on SBERT benchmarks (STS, semantic search)");
                System.out.println("  • Upgrade path: try NeuML/pubmedbert-base-embeddings for even better medical quality");
            }

            System.out.println();
            System.out.println("  To switch in application.properties:");
            String modelKey = best.name().contains("pubmed")
                ? "https://huggingface.co/Dev-SN/pubmedbert-base-embeddings-onnx/resolve/main/onnx/model.onnx"
                : "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/onnx/model.onnx";
            String tokenKey = best.name().contains("pubmed")
                ? "https://huggingface.co/Dev-SN/pubmedbert-base-embeddings-onnx/resolve/main/tokenizer.json"
                : "https://huggingface.co/sentence-transformers/all-mpnet-base-v2/resolve/main/tokenizer.json";
            System.out.println("    spring.ai.embedding.transformer.onnx.model-uri=" + modelKey);
            System.out.println("    spring.ai.embedding.transformer.tokenizer.uri=" + tokenKey);
        }

        System.out.println();
        System.out.println("  Research summary (models not tested here due to ONNX/network constraints):");
        System.out.println("  • NeuML/pubmedbert-base-embeddings  — TOP PICK for biomedical NLP");
        System.out.println("    PubMedBERT(768d) fine-tuned for semantic similarity. Scores >0.80 on");
        System.out.println("    biomedical STS benchmarks. Significantly outperforms general models");
        System.out.println("    on clinical abbreviation resolution (WBC, SBP, GCS, EF, etc.).");
        System.out.println("    NOTE: NeuML repo has no ONNX export; ONNX via Dev-SN (same weights).");
        System.out.println("  • sentence-transformers/all-mpnet-base-v2  — best general-purpose upgrade");
        System.out.println("    from MiniLM. 768-dim, higher recall on semantic similarity tasks.");
        System.out.println("  • dmis-lab/biobert-base-cased-v1.2  — BioBERT on PubMed+PMC, but no");
        System.out.println("    sentence-similarity fine-tuning → lower STS scores than pubmedbert.");
        System.out.println("  • emilyalsentzer/Bio_ClinicalBERT  — clinical notes focus (MIMIC-III),");
        System.out.println("    good for clinical text but weaker on column-header abbreviations.");
        System.out.println("═".repeat(80));
        System.out.println();
    }

    // ─── Result record ─────────────────────────────────────────────────────────

    record ModelResult(
        String name,
        boolean loaded,
        String skipReason,
        int dims,
        double meanSim,
        long aboveThreshold,
        int grouped,
        List<Double> sims
    ) {
        static ModelResult skipped(String name, String reason) {
            return new ModelResult(name, false, reason, 0, 0, 0, 0, List.of());
        }
    }
}
