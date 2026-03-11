package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Generates plain-language descriptions for dataset columns and their values.
 *
 * <p>Delegates to the OpenMed Python microservice ({@link OpenMedDescriptionService}) as
 * the primary engine.  When OpenMed is unavailable or returns an empty result, a simple
 * text-normalisation fallback is used (no generative LLM required).</p>
 */
@Service
public class DescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(DescriptionService.class);

    private final OpenMedDescriptionService openMedDescriptionService;
    private final ExecutorService descExecutor;

    @Value("${description.batchSize:6}")
    private int batchSize;

    @Value("${description.maxValuesPerColumn:10}")
    private int maxValuesPerColumn;

    @Value("${description.timeoutSeconds:40}")
    private int timeoutSeconds;

    /** Spring-managed constructor – wires OpenMed via {@link ObjectProvider}. */
    public DescriptionService(ObjectProvider<OpenMedDescriptionService> openMedDescProvider,
                              @Qualifier("llmExecutor") ExecutorService descExecutor) {
        this.openMedDescriptionService = openMedDescProvider.getIfAvailable();
        this.descExecutor = descExecutor;
    }

    /** Test constructor – accepts explicit OpenMed service (may be {@code null}). */
    public DescriptionService(OpenMedDescriptionService openMedDescriptionService,
                              ExecutorService descExecutor) {
        this.openMedDescriptionService = openMedDescriptionService;
        this.descExecutor = descExecutor;
    }

    public record ValueSpec(String v, String min, String max) {}

    public record ColumnEnrichmentInput(
            String colKey,
            String terminology,
            List<ValueSpec> values
    ) {}

    public record EnrichmentResult(
            String colDesc,
            Map<String, String> valueDescByValue
    ) {}

    public int batchSize() {
        return Math.max(1, batchSize);
    }

    /**
     * Batch enrichment for multiple columns.  Returns a map keyed by colKey → EnrichmentResult.
     */
    public CompletableFuture<Map<String, EnrichmentResult>> generateEnrichmentBatchAsync(
            List<ColumnEnrichmentInput> inputs) {

        final List<ColumnEnrichmentInput> safeInputs = normalizeInputs(inputs);

        return CompletableFuture
                .supplyAsync(() -> generateEnrichmentBatchInternal(safeInputs), descExecutor)
                .orTimeout(Math.max(5, timeoutSeconds), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    logger.warn("[DescriptionService] Batch enrichment failed: {}", ex.toString());
                    return fallbackResults(safeInputs);
                });
    }

    private Map<String, EnrichmentResult> generateEnrichmentBatchInternal(
            List<ColumnEnrichmentInput> inputs) {

        if (inputs == null || inputs.isEmpty()) return Map.of();

        // --- Primary: OpenMed description service ---
        if (openMedDescriptionService != null) {
            try {
                Map<String, EnrichmentResult> openMedResult =
                        openMedDescriptionService.describeColumns(inputs);
                if (openMedResult != null && !openMedResult.isEmpty()) {
                    logger.info("[DescriptionService] OpenMed provided {} description(s)",
                            openMedResult.size());
                    Map<String, EnrichmentResult> out = new LinkedHashMap<>(openMedResult);
                    for (ColumnEnrichmentInput in : inputs) {
                        String ck = safe(in == null ? null : in.colKey()).trim();
                        if (!ck.isEmpty()) {
                            out.putIfAbsent(ck, new EnrichmentResult(cleanSentence(ck, ck), Map.of()));
                        }
                    }
                    return out;
                }
                logger.info("[DescriptionService] OpenMed returned empty; using text-normalisation fallback");
            } catch (Exception e) {
                logger.warn("[DescriptionService] OpenMed failed ({}); using text-normalisation fallback",
                        e.toString());
            }
        }

        // --- Fallback: text normalisation (no external call) ---
        return fallbackResults(inputs);
    }

    // ------------------------------------------------------------------
    // Normalisation helpers
    // ------------------------------------------------------------------

    private List<ColumnEnrichmentInput> normalizeInputs(List<ColumnEnrichmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();

        List<ColumnEnrichmentInput> out = new ArrayList<>(inputs.size());
        Set<String> seen = new LinkedHashSet<>();

        for (ColumnEnrichmentInput in : inputs) {
            if (in == null) continue;
            String ck = safe(in.colKey()).trim();
            if (ck.isEmpty()) continue;
            if (!seen.add(ck)) continue;

            String term = safe(in.terminology()).trim();
            List<ValueSpec> vals = limitAndNormalizeValues(in.values());
            out.add(new ColumnEnrichmentInput(ck, term, vals));
        }
        return out;
    }

    private List<ValueSpec> limitAndNormalizeValues(List<ValueSpec> values) {
        if (values == null || values.isEmpty()) return List.of();

        List<ValueSpec> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int lim = Math.max(1, maxValuesPerColumn);

        for (ValueSpec vs : values) {
            if (vs == null) continue;
            String v = safe(vs.v()).trim();
            if (v.isEmpty() || !seen.add(v)) continue;
            String min = safe(vs.min()).trim();
            String max = safe(vs.max()).trim();
            out.add(new ValueSpec(v, min.isEmpty() ? null : min, max.isEmpty() ? null : max));
            if (out.size() >= lim) break;
        }
        return out;
    }

    private Map<String, EnrichmentResult> fallbackResults(List<ColumnEnrichmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return Map.of();

        Map<String, EnrichmentResult> out = new LinkedHashMap<>();
        for (ColumnEnrichmentInput in : inputs) {
            if (in == null) continue;
            String ck = safe(in.colKey()).trim();
            if (ck.isEmpty()) continue;
            out.put(ck, new EnrichmentResult(cleanSentence(ck, ck), identityValueMap(in.values())));
        }
        return out;
    }

    private Map<String, String> identityValueMap(List<ValueSpec> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (ValueSpec vs : values) {
            if (vs == null) continue;
            String v = safe(vs.v()).trim();
            if (!v.isEmpty()) out.put(v, v);
        }
        return out;
    }

    private static String cleanSentence(String s, String fallback) {
        String x = safe(s).trim();
        if (x.isEmpty()) x = safe(fallback).trim();
        if (x.isEmpty()) x = "field";
        if (x.startsWith("\"") && x.endsWith("\"") && x.length() >= 2) x = x.substring(1, x.length() - 1).trim();
        if (!x.isEmpty()) x = Character.toUpperCase(x.charAt(0)) + x.substring(1);
        if (!x.matches(".*[.!?]$")) x += ".";
        return x;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
