package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class DescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(DescriptionService.class);

    private final LLMTextGenerator llm;
    private final ExecutorService llmExecutor;
    private final ObjectMapper om = new ObjectMapper();
    private final ConcurrentMap<String, EnrichmentResult> cache = new ConcurrentHashMap<>();

    @Value("${description.batchSize:6}")
    private int batchSize;

    @Value("${description.maxValuesPerColumn:10}")
    private int maxValuesPerColumn;

    @Value("${description.maxPromptChars:12000}")
    private int maxPromptChars;

    @Value("${description.timeoutSeconds:40}")
    private int timeoutSeconds;

    @Value("${description.maxRetries:2}")
    private int maxRetries;

    public DescriptionService(LLMTextGenerator llm,
                              @Qualifier("llmExecutor") ExecutorService llmExecutor) {
        this.llm = llm;
        this.llmExecutor = llmExecutor;
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

    public void clearCache() {
        cache.clear();
    }

    /**
     * Batch enrichment for multiple columns in ONE LLM call (per batch).
     * Returns a map keyed by colKey -> EnrichmentResult.
     */
    public CompletableFuture<Map<String, EnrichmentResult>> generateEnrichmentBatchAsync(List<ColumnEnrichmentInput> inputs) {
        final List<ColumnEnrichmentInput> safeInputs = normalizeInputs(inputs);

        return CompletableFuture
                .supplyAsync(() -> generateEnrichmentBatchInternal(safeInputs), llmExecutor)
                .orTimeout(Math.max(5, timeoutSeconds), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    logger.warn("[DescriptionService] Batch enrichment failed: {}", ex.toString());
                    return fallbackResults(safeInputs);
                });
    }

    private Map<String, EnrichmentResult> generateEnrichmentBatchInternal(List<ColumnEnrichmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return Collections.emptyMap();

        // If LLM is disabled, return minimal safe defaults (no LLM calls).
        if (llm == null || !llm.isEnabled()) {
            return fallbackResults(inputs);
        }

        // Split cached vs missing, and always return results for all requested colKeys.
        Map<String, EnrichmentResult> out = new LinkedHashMap<>();
        List<ColumnEnrichmentInput> missing = new ArrayList<>();

        for (ColumnEnrichmentInput in : inputs) {
            if (in == null) continue;
            String colKey = safe(in.colKey).trim();
            if (colKey.isEmpty()) continue;

            String term = safe(in.terminology).trim();
            List<ValueSpec> vals = limitAndNormalizeValues(in.values);

            String cacheKey = makeCacheKey(colKey, term, vals);
            EnrichmentResult cached = cache.get(cacheKey);

            if (cached != null) {
                out.put(colKey, ensureRequestedValuesPresent(cached, vals, colKey));
            } else {
                missing.add(new ColumnEnrichmentInput(colKey, term, vals));
            }
        }

        if (missing.isEmpty()) {
            // Ensure every requested column has an entry (even if inputs had duplicates).
            for (ColumnEnrichmentInput in : inputs) {
                String ck = safe(in == null ? null : in.colKey).trim();
                if (!ck.isEmpty()) out.putIfAbsent(ck, new EnrichmentResult(cleanSentence(ck, ck), Map.of()));
            }
            return out;
        }

        // We call the LLM only for missing entries.
        String prompt = buildBatchPrompt(missing);
        if (prompt.length() > Math.max(1000, maxPromptChars)) {
            prompt = prompt.substring(0, Math.max(1000, maxPromptChars));
        }

        Map<String, EnrichmentResult> parsed = null;

        int attempts = Math.max(1, maxRetries);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                String raw = llm.generate(prompt);
                parsed = parseBatchEnrichment(raw);
                if (parsed != null && !parsed.isEmpty()) break;
                logger.warn("[DescriptionService] empty/invalid JSON (attempt {}/{})", attempt, attempts);
            } catch (Exception e) {
                logger.warn("[DescriptionService] LLM call failed (attempt {}/{}): {}", attempt, attempts, e.toString());
            }
        }

        if (parsed == null) parsed = Map.of();

        // Post-process, cache, and merge into output. Always provide defaults if missing.
        for (ColumnEnrichmentInput in : missing) {
            String colKey = safe(in.colKey).trim();
            if (colKey.isEmpty()) continue;

            List<ValueSpec> vals = in.values == null ? List.of() : in.values;
            EnrichmentResult r = parsed.get(colKey);

            EnrichmentResult finalRes;
            if (r == null) {
                finalRes = new EnrichmentResult(cleanSentence(colKey, colKey), identityValueMap(vals));
            } else {
                String cd = cleanSentence(r.colDesc, colKey);

                Map<String, String> vmap = new LinkedHashMap<>();
                Map<String, String> rawMap = r.valueDescByValue == null ? Map.of() : r.valueDescByValue;
                for (ValueSpec vs : vals) {
                    if (vs == null) continue;
                    String v = safe(vs.v).trim();
                    if (v.isEmpty()) continue;
                    String d = rawMap.get(v);
                    if (isBlank(d)) d = v;
                    vmap.put(v, cleanSentence(d, v));
                }

                finalRes = new EnrichmentResult(cd, vmap);
            }

            String term = safe(in.terminology).trim();
            String cacheKey = makeCacheKey(colKey, term, vals);
            cache.put(cacheKey, finalRes);

            out.put(colKey, finalRes);
        }

        // Ensure every requested column has an entry
        for (ColumnEnrichmentInput in : inputs) {
            String ck = safe(in == null ? null : in.colKey).trim();
            if (!ck.isEmpty()) out.putIfAbsent(ck, new EnrichmentResult(cleanSentence(ck, ck), Map.of()));
        }

        return out;
    }

    private List<ColumnEnrichmentInput> normalizeInputs(List<ColumnEnrichmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();

        List<ColumnEnrichmentInput> out = new ArrayList<>(inputs.size());
        Set<String> seen = new LinkedHashSet<>();

        for (ColumnEnrichmentInput in : inputs) {
            if (in == null) continue;
            String ck = safe(in.colKey).trim();
            if (ck.isEmpty()) continue;

            // Keep first occurrence for deterministic behavior; caller can pre-dedupe if desired.
            if (!seen.add(ck)) continue;

            String term = safe(in.terminology).trim();
            List<ValueSpec> vals = limitAndNormalizeValues(in.values);

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
            String v = safe(vs.v).trim();
            if (v.isEmpty()) continue;
            if (!seen.add(v)) continue;

            String min = safe(vs.min).trim();
            String max = safe(vs.max).trim();
            out.add(new ValueSpec(v, min.isEmpty() ? null : min, max.isEmpty() ? null : max));

            if (out.size() >= lim) break;
        }

        return out;
    }

    private String buildBatchPrompt(List<ColumnEnrichmentInput> inputs) {
        List<Map<String, Object>> payload = new ArrayList<>();

        for (ColumnEnrichmentInput in : inputs) {
            if (in == null) continue;
            String colKey = safe(in.colKey).trim();
            if (colKey.isEmpty()) continue;

            String terminologyLabel = "";
            String terminology = safe(in.terminology).trim();
            if (!isBlank(terminology) && !terminology.startsWith("CONCEPT_")) {
                terminologyLabel = extractLabelFromCodePipeLabel(terminology);
            }

            List<Map<String, Object>> vals = new ArrayList<>();
            if (in.values != null) {
                for (ValueSpec vs : in.values) {
                    if (vs == null) continue;
                    String v = safe(vs.v).trim();
                    if (v.isEmpty()) continue;

                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("v", v);
                    String min = safe(vs.min).trim();
                    String max = safe(vs.max).trim();
                    if (!min.isEmpty() && !max.isEmpty()) {
                        o.put("min", min);
                        o.put("max", max);
                    }
                    vals.add(o);
                }
            }

            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("colKey", colKey);
            obj.put("terminology", terminologyLabel);
            obj.put("values", vals);
            payload.add(obj);
        }

        String json;
        try {
            json = om.writeValueAsString(payload);
        } catch (Exception e) {
            json = "[]";
        }

        return ""
                + "Return ONLY minified JSON. No markdown. No extra keys. No trailing text.\n"
                + "Goal: brief functional meaning per column and per value.\n"
                + "Style: FIM/Barthel/Section GG/Katz-like anchors.\n"
                + "Hard limits: col_desc<=90 chars; each value d<=70 chars.\n"
                + "Do not add causes, complications, prevalence, examples, or long medical text.\n"
                + "If ambiguous: neutral mapping.\n"
                + "Binary template: \"Yes\"=\"present\", \"No\"=\"absent\".\n"
                + "Ordinal template: low=more dependent/less function; high=more independent/more function unless clearly opposite.\n"
                + "If values look numeric/ordinal, keep anchors consistent and directional.\n"
                + "Output schema:\n"
                + "{\"columns\":[{\"colKey\":\"...\",\"col_desc\":\"...\",\"values\":[{\"v\":\"...\",\"d\":\"...\"}]}]}\n"
                + "Input:\n"
                + json;
    }

    private Map<String, EnrichmentResult> parseBatchEnrichment(String raw) {
        String s = safe(raw).trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        s = s.substring(start, end + 1).trim();

        try {
            Map<String, Object> m = om.readValue(s, new TypeReference<>() {});
            Object colsObj = m.get("columns");
            if (!(colsObj instanceof List<?> cols)) return null;

            Map<String, EnrichmentResult> out = new LinkedHashMap<>();

            for (Object c : cols) {
                if (!(c instanceof Map<?, ?> cm)) continue;

                String colKey = toStr(cm.get("colKey")).trim();
                if (colKey.isEmpty()) continue;

                String colDesc = toStr(cm.get("col_desc")).trim();
                if (colDesc.isEmpty()) colDesc = colKey;

                Map<String, String> vmap = new LinkedHashMap<>();
                Object valsObj = cm.get("values");
                if (valsObj instanceof List<?> vals) {
                    for (Object v : vals) {
                        if (!(v instanceof Map<?, ?> vm)) continue;

                        String vv = toStr(vm.get("v")).trim();
                        if (vv.isEmpty()) continue;

                        String dd = toStr(vm.get("d")).trim();
                        if (dd.isEmpty()) dd = vv;

                        vmap.put(vv, dd);
                    }
                }

                out.put(colKey, new EnrichmentResult(colDesc, vmap));
            }

            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, EnrichmentResult> fallbackResults(List<ColumnEnrichmentInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return Map.of();

        Map<String, EnrichmentResult> out = new LinkedHashMap<>();
        for (ColumnEnrichmentInput in : inputs) {
            if (in == null) continue;
            String ck = safe(in.colKey).trim();
            if (ck.isEmpty()) continue;
            out.put(ck, new EnrichmentResult(cleanSentence(ck, ck), identityValueMap(in.values)));
        }
        return out;
    }

    private EnrichmentResult ensureRequestedValuesPresent(EnrichmentResult r, List<ValueSpec> requested, String colKey) {
        if (r == null) return new EnrichmentResult(cleanSentence(colKey, colKey), identityValueMap(requested));

        String cd = cleanSentence(r.colDesc, colKey);

        Map<String, String> base = r.valueDescByValue == null ? Map.of() : r.valueDescByValue;
        Map<String, String> vmap = new LinkedHashMap<>(base.size() + 8);

        // Keep existing first
        for (Map.Entry<String, String> e : base.entrySet()) {
            String k = safe(e.getKey()).trim();
            if (k.isEmpty()) continue;
            String d = safe(e.getValue()).trim();
            if (d.isEmpty()) d = k;
            vmap.put(k, cleanSentence(d, k));
        }

        // Ensure requested exist
        if (requested != null) {
            for (ValueSpec vs : requested) {
                if (vs == null) continue;
                String v = safe(vs.v).trim();
                if (v.isEmpty()) continue;
                vmap.putIfAbsent(v, v);
            }
        }

        return new EnrichmentResult(cd, vmap);
    }

    private Map<String, String> identityValueMap(List<ValueSpec> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (ValueSpec vs : values) {
            if (vs == null) continue;
            String v = safe(vs.v).trim();
            if (!v.isEmpty()) out.put(v, v);
        }
        return out;
    }

    private String makeCacheKey(String colKey, String terminology, List<ValueSpec> values) {
        int h = (values == null) ? 0 : values.hashCode();
        return safe(colKey).trim() + "|" + safe(terminology).trim() + "|" + h;
    }

    // Expect "code|label" but tolerate plain code/label.
    private String extractLabelFromCodePipeLabel(String terminology) {
        String t = safe(terminology).trim();
        if (t.isEmpty()) return "";
        int bar = t.indexOf('|');
        if (bar >= 0 && bar < t.length() - 1) {
            return t.substring(bar + 1).trim();
        }
        return t;
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

    private static String toStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
