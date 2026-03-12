package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.model.ColumnRecord;
import org.taniwha.service.DescriptionService;
import org.taniwha.service.OpenMedTerminologyService;
import org.taniwha.service.TerminologyLookupService;
import org.taniwha.service.jobs.ProgressReporter;
import org.taniwha.util.StringUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Orchestrates the terminology-lookup and description-generation pipeline that enriches a
 * set of {@link SuggestedMappingDTO} objects after initial column matching.
 * <p>
 * Extracted from {@link MappingService} for maintainability.
 * </p>
 */
class MappingEnrichmentHelper {

    private static final Logger logger = LoggerFactory.getLogger(MappingEnrichmentHelper.class);

    private final OpenMedTerminologyService openMedTerminologyService;
    private final TerminologyLookupService terminologyLookupService;
    private final DescriptionService descriptionGenerator;
    private final MappingServiceSettings settings;

    MappingEnrichmentHelper(
            OpenMedTerminologyService openMedTerminologyService,
            TerminologyLookupService terminologyLookupService,
            DescriptionService descriptionGenerator,
            MappingServiceSettings settings
    ) {
        this.openMedTerminologyService = openMedTerminologyService;
        this.terminologyLookupService = terminologyLookupService;
        this.descriptionGenerator = descriptionGenerator;
        this.settings = settings;
    }

    // ------------------------------------------------------------------
    // Post-processing: description fallbacks
    // ------------------------------------------------------------------

    /**
     * Guarantees that every mapping entry has a non-empty description and non-null terminology.
     */
    void ensureNonEmptyMappingDescriptions(List<Map<String, SuggestedMappingDTO>> allMappings) {
        for (Map<String, SuggestedMappingDTO> mappingMap : allMappings) {
            if (mappingMap == null) continue;
            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                String columnKey = entry.getKey();
                SuggestedMappingDTO mapping = entry.getValue();
                if (mapping == null) continue;
                String d = mapping.getDescription();
                if (d == null || d.trim().isEmpty()) mapping.setDescription(columnKey);
                if (mapping.getTerminology() == null) mapping.setTerminology("");
            }
        }
    }

    // ------------------------------------------------------------------
    // Main enrichment pipeline
    // ------------------------------------------------------------------

    /**
     * Runs the full enrichment pipeline:
     * <ol>
     *   <li>Infers SNOMED search terms per column/value via the OpenMed service (primary)
     *       or the LLM inference service (fallback), both batched.</li>
     *   <li>Looks up SNOMED codes in bulk (with one retry for missing keys).
     *       When Snowstorm returns nothing, the OpenMed-suggested term itself is kept.</li>
     *   <li>Applies terminology codes to mapping DTOs.</li>
     *   <li>Generates plain-language descriptions via the LLM (async, windowed).</li>
     * </ol>
     */
    void populateTerminologyAndDescriptionsBatch(
            List<Map<String, SuggestedMappingDTO>> allMappings,
            ProgressReporter cb
    ) {
        if (allMappings == null || allMappings.isEmpty()) return;
        if (cb != null) cb.report(5, "Inferring terminology search terms…");

        List<ColumnRecord> colInputs = getColumnInputs(allMappings);

        // Use OpenMed batch size when available, fall back to default.
        int inferBatchSize = openMedTerminologyService != null
                ? openMedTerminologyService.batchSize()
                : 5;
        if (inferBatchSize <= 0) inferBatchSize = 5;

        Map<String, String> colLookupKeyByCol = new LinkedHashMap<>();
        Map<String, String> valLookupKeyByValueKey = new LinkedHashMap<>();

        int inferredCols = 0;
        int totalInferBatches = colInputs.isEmpty() ? 0
                : ((colInputs.size() + inferBatchSize - 1) / inferBatchSize);
        int inferBatchNo = 0;

        // Snowstorm futures: one per inference batch, submitted immediately after each
        // OpenMed batch completes so that Snowstorm lookups run in parallel with the
        // next inference batch rather than waiting for all inference to finish first.
        List<CompletableFuture<Map<String, String>>> snowstormFutures = new ArrayList<>();
        Set<String> submittedLookupKeys = new HashSet<>();

        // ------------------------------------------------------------------
        // 1) Terminology inference (batched) – OpenMed + immediate Snowstorm submit
        // ------------------------------------------------------------------
        for (int i = 0; i < colInputs.size(); i += inferBatchSize) {
            int end = Math.min(colInputs.size(), i + inferBatchSize);
            List<ColumnRecord> batch = colInputs.subList(i, end);

            inferBatchNo++;
            if (cb != null) {
                double frac = (inferBatchNo - 1) / (double) Math.max(1, totalInferBatches);
                int pct = Math.max(5, Math.min(29, 5 + (int) Math.floor(frac * 25.0)));
                cb.report(pct, "Inferring terminology… (batch " + inferBatchNo + "/" + totalInferBatches + ")");
            }

            List<OpenMedTerminologyService.InferredTerm> inferred = List.of();
            if (openMedTerminologyService != null) {
                inferred = openMedTerminologyService.inferBatch(batch);
                if (!inferred.isEmpty()) {
                    logger.info("[TermInfer/OpenMed] batch {}/{} -> inferred {}",
                            inferBatchNo, Math.max(1, totalInferBatches), inferred.size());
                }
            }

            if (inferred.isEmpty()) {
                for (ColumnRecord ci : batch) {
                    if (ci == null) continue;
                    String colKey = StringUtil.safeTrim(ci.colKey());
                    if (colKey.isEmpty()) continue;
                    colLookupKeyByCol.put(colKey, colKey + "|" + colKey);
                    if (ci.values() != null) {
                        for (String rv : ci.values()) {
                            String v = StringUtil.safeTrim(rv);
                            if (!v.isEmpty()) valLookupKeyByValueKey.put(colKey + "|" + v, colKey + "|" + v);
                        }
                    }
                }
                logger.info("[TermInfer] batch {}/{} -> inferred {}", inferBatchNo, Math.max(1, totalInferBatches), 0);
            } else {
                for (OpenMedTerminologyService.InferredTerm it : inferred) {
                    if (it == null) continue;
                    String colKey        = StringUtil.safeTrim(it.colKey());
                    String colSearchTerm = StringUtil.safeTrim(it.colSearchTerm());
                    Map<String, String> vmap = it.valueSearchTerms() == null ? Map.of() : it.valueSearchTerms();

                    if (colKey.isEmpty()) continue;
                    if (colSearchTerm.isEmpty()) colSearchTerm = colKey;
                    colLookupKeyByCol.put(colKey, colKey + "|" + colSearchTerm);

                    for (Map.Entry<String, String> ve : vmap.entrySet()) {
                        String rawVal = StringUtil.safeTrim(ve.getKey());
                        String vTerm  = StringUtil.safeTrim(ve.getValue());
                        if (!rawVal.isEmpty() && !vTerm.isEmpty()) {
                            valLookupKeyByValueKey.put(colKey + "|" + rawVal, colKey + "|" + vTerm);
                        }
                    }
                    inferredCols++;
                }
            }

            // Submit Snowstorm lookups for THIS batch immediately without waiting for
            // the next inference batch to start.
            if (terminologyLookupService != null) {
                List<TerminologyLookupService.TerminologyRequest> batchSnowReqs = new ArrayList<>();
                for (ColumnRecord ci : batch) {
                    if (ci == null) continue;
                    String colKey = StringUtil.safeTrim(ci.colKey());
                    if (colKey.isEmpty()) continue;

                    String colLkup = colLookupKeyByCol.get(colKey);
                    if (colLkup != null && !StringUtil.safeTrim(colLkup).isEmpty()
                            && submittedLookupKeys.add(colLkup)) {
                        batchSnowReqs.add(new TerminologyLookupService.TerminologyRequest(colLkup, null));
                    }
                    if (ci.values() != null) {
                        for (String rv : ci.values()) {
                            String v = StringUtil.safeTrim(rv);
                            if (v.isEmpty()) continue;
                            String vLkup = valLookupKeyByValueKey.get(colKey + "|" + v);
                            if (vLkup != null && !StringUtil.safeTrim(vLkup).isEmpty()
                                    && submittedLookupKeys.add(vLkup)) {
                                batchSnowReqs.add(new TerminologyLookupService.TerminologyRequest(vLkup, colKey));
                            }
                        }
                    }
                }
                if (!batchSnowReqs.isEmpty()) {
                    snowstormFutures.add(terminologyLookupService.submitLookupAsync(batchSnowReqs));
                    logger.info("[TermLookup] batch {}/{}: submitted {} Snowstorm requests (async)",
                            inferBatchNo, Math.max(1, totalInferBatches), batchSnowReqs.size());
                }
            }

            if (cb != null) {
                int pct = Math.max(5, Math.min(30, 5 + (int) Math.floor(
                        inferBatchNo / (double) Math.max(1, totalInferBatches) * 25.0)));
                cb.report(pct, "Inferring terminology… (" + inferBatchNo + "/" + totalInferBatches + ")");
            }
        }

        logger.info("[MappingService] terminology inference: cols={}, inferred={}", colInputs.size(), inferredCols);
        if (cb != null) cb.report(30, "Terminology inference complete.");
        if (cb != null) cb.report(35, "Waiting for SNOMED lookups…");

        // ------------------------------------------------------------------
        // 2) Collect Snowstorm results (futures were submitted concurrently above)
        // ------------------------------------------------------------------
        Map<String, String> terminologyResults = new LinkedHashMap<>();
        for (CompletableFuture<Map<String, String>> f : snowstormFutures) {
            try {
                Map<String, String> partial = f.join();
                if (partial != null) terminologyResults.putAll(partial);
            } catch (Exception e) {
                logger.warn("[TermLookup] Snowstorm future failed: {}", e.toString());
            }
        }

        // Retry any keys that are still missing from results (once)
        int missing = 0;
        List<TerminologyLookupService.TerminologyRequest> retryReqs = new ArrayList<>();
        Set<String> retrySeenReq = new HashSet<>();
        for (Map.Entry<String, String> e : colLookupKeyByCol.entrySet()) {
            String lookupKey = StringUtil.safeTrim(e.getValue());
            if (!lookupKey.isEmpty() && !terminologyResults.containsKey(lookupKey)
                    && retrySeenReq.add(lookupKey)) {
                retryReqs.add(new TerminologyLookupService.TerminologyRequest(lookupKey, null));
                missing++;
            }
        }
        for (Map.Entry<String, String> e : valLookupKeyByValueKey.entrySet()) {
            String valueKey  = e.getKey();
            String lookupKey = StringUtil.safeTrim(e.getValue());
            String colKey    = "";
            int bar = valueKey.indexOf('|');
            if (bar > 0) colKey = valueKey.substring(0, bar);
            if (!lookupKey.isEmpty() && !terminologyResults.containsKey(lookupKey)
                    && retrySeenReq.add(lookupKey)) {
                retryReqs.add(new TerminologyLookupService.TerminologyRequest(lookupKey, colKey));
                missing++;
            }
        }
        if (missing > 0 && terminologyLookupService != null) {
            logger.warn("[MappingService] SNOMED missing {} keys, retrying once", missing);
            Map<String, String> retry = terminologyLookupService.batchLookupTerminology(retryReqs);
            if (retry != null && !retry.isEmpty()) {
                terminologyResults.putAll(retry);
            }
        }

        logger.info("[MappingService] SNOMED lookup done: returned={}", terminologyResults.size());
        if (cb != null) cb.report(60, "SNOMED lookup complete. Applying terminologies…");

        // ------------------------------------------------------------------
        // 3) Apply terminology + build description task list
        // ------------------------------------------------------------------
        record EnrichTask(String colKey, SuggestedMappingDTO mapping, List<DescriptionService.ValueSpec> valueSpecs) {}

        List<EnrichTask> tasks = new ArrayList<>();
        int colCount = 0;

        for (Map<String, SuggestedMappingDTO> mappingMap : allMappings) {
            if (mappingMap == null) continue;
            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                String columnKey = entry.getKey();
                SuggestedMappingDTO mapping = entry.getValue();
                if (mapping == null) continue;

                final String colKey = Optional.ofNullable(columnKey)
                        .map(String::trim).filter(s -> !s.isEmpty()).orElse("field");

                String colLookupKey = colLookupKeyByCol.getOrDefault(colKey, colKey + "|" + colKey);
                String columnTerminology = resolveTerminology(colLookupKey, terminologyResults);
                mapping.setTerminology(columnTerminology);

                if (mapping.getGroups() != null) {
                    for (SuggestedGroupDTO group : mapping.getGroups()) {
                        if (group == null || group.getValues() == null) continue;
                        for (SuggestedValueDTO value : group.getValues()) {
                            if (value == null) continue;
                            String valueName = StringUtil.safeTrim(value.getName());
                            if (valueName.isEmpty()) continue;
                            String vLookupKey = valLookupKeyByValueKey.get(colKey + "|" + valueName);
                            String valueTerminology = vLookupKey == null ? ""
                                    : resolveTerminology(vLookupKey, terminologyResults);
                            value.setTerminology(valueTerminology);
                        }
                    }
                }

                if (colCount < settings.maxColumnDescTasks()) {
                    tasks.add(new EnrichTask(colKey, mapping, extractValueSpecs(mapping)));
                    colCount++;
                } else {
                    applyFallbackDescriptions(colKey, mapping);
                }
            }
        }

        // ------------------------------------------------------------------
        // 4) Generate descriptions (async, windowed)
        // ------------------------------------------------------------------
        if (cb != null) cb.report(65, "Terminologies applied. Generating descriptions…");
        final int totalColsToDescribe = tasks.size();

        IntConsumer reportProgress = doneCols -> {
            if (cb == null) return;
            if (totalColsToDescribe == 0) { cb.report(100, "Done."); return; }
            int pct = Math.max(65, Math.min(99,
                    65 + (int) Math.floor(doneCols / (double) totalColsToDescribe * 35.0)));
            cb.report(pct, "Generating descriptions… (" + doneCols + "/" + totalColsToDescribe + ")");
        };

        final AtomicInteger doneCols = new AtomicInteger(0);

        Consumer<List<CompletableFuture<Void>>> flushWindow = window -> {
            if (window.isEmpty()) return;
            CompletableFuture.allOf(window.toArray(new CompletableFuture[0])).join();
            window.clear();
        };

        List<List<EnrichTask>> descBatches = partition(tasks, Math.max(1, settings.descriptionBatchColumns()));
        List<CompletableFuture<Void>> window = new ArrayList<>(settings.descriptionConcurrencyWindow());

        int batchNo = 0;
        for (List<EnrichTask> batch : descBatches) {
            batchNo++;
            final int thisBatchNo = batchNo;

            List<DescriptionService.ColumnEnrichmentInput> inputs = new ArrayList<>(batch.size());
            for (EnrichTask t : batch) {
                if (t == null || t.mapping() == null) continue;
                inputs.add(new DescriptionService.ColumnEnrichmentInput(
                        t.colKey(),
                        StringUtil.safe(t.mapping().getTerminology()),
                        t.valueSpecs()
                ));
            }
            if (inputs.isEmpty()) continue;

            window.add(
                    descriptionGenerator
                            .generateEnrichmentBatchAsync(inputs)
                            .thenAccept(results -> {
                                for (EnrichTask t : batch) {
                                    if (t == null || t.mapping() == null) continue;
                                    applyDescriptionResult(t.colKey(), t.mapping(), results, doneCols, reportProgress);
                                }
                                if (thisBatchNo <= 3) {
                                    logger.info("[DESC-BATCH] completed batch {}/{} (cols={})",
                                            thisBatchNo, descBatches.size(), batch.size());
                                }
                            })
                            .exceptionally(ex -> {
                                logger.warn("[DESC-BATCH] failed batch {}/{} ({})",
                                        thisBatchNo, descBatches.size(), ex.toString());
                                for (EnrichTask t : batch) {
                                    if (t == null || t.mapping() == null) continue;
                                    applyFallbackDescriptions(t.colKey(), t.mapping());
                                    reportProgress.accept(doneCols.incrementAndGet());
                                }
                                return null;
                            })
            );

            if (window.size() >= settings.descriptionConcurrencyWindow()) flushWindow.accept(window);
        }

        flushWindow.accept(window);
        if (cb != null) cb.report(100, "Done.");
    }

    // ------------------------------------------------------------------
    // Description helpers
    // ------------------------------------------------------------------

    private void applyDescriptionResult(
            String colKey,
            SuggestedMappingDTO mapping,
            Map<String, DescriptionService.EnrichmentResult> results,
            AtomicInteger doneCols,
            IntConsumer reportProgress
    ) {
        DescriptionService.EnrichmentResult r = (results == null) ? null : results.get(colKey);

        if (r != null) {
            String cd = r.colDesc();
            if (cd != null && !cd.trim().isEmpty()) {
                mapping.setDescription(cd);
            } else if (mapping.getDescription() == null || mapping.getDescription().trim().isEmpty()) {
                mapping.setDescription(colKey);
            }

            Map<String, String> vm = r.valueDescByValue();
            if (mapping.getGroups() != null && vm != null) {
                for (SuggestedGroupDTO group : mapping.getGroups()) {
                    if (group == null || group.getValues() == null) continue;
                    for (SuggestedValueDTO value : group.getValues()) {
                        if (value == null) continue;
                        String vn = StringUtil.safeTrim(value.getName());
                        if (vn.isEmpty()) continue;
                        String vd = vm.get(vn);
                        if (vd != null && !vd.trim().isEmpty()) value.setDescription(vd);
                        else if (value.getDescription() == null || value.getDescription().trim().isEmpty())
                            value.setDescription(vn);
                    }
                }
            }
        } else {
            applyFallbackDescriptions(colKey, mapping);
        }

        reportProgress.accept(doneCols.incrementAndGet());
    }

    private void applyFallbackDescriptions(String colKey, SuggestedMappingDTO mapping) {
        if (mapping.getDescription() == null || mapping.getDescription().trim().isEmpty()) {
            mapping.setDescription(colKey);
        }
        if (mapping.getGroups() != null) {
            for (SuggestedGroupDTO group : mapping.getGroups()) {
                if (group == null || group.getValues() == null) continue;
                for (SuggestedValueDTO value : group.getValues()) {
                    if (value == null) continue;
                    String vn = StringUtil.safeTrim(value.getName());
                    if (!vn.isEmpty() && (value.getDescription() == null || value.getDescription().trim().isEmpty())) {
                        value.setDescription(vn);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Column-input extraction
    // ------------------------------------------------------------------

    private static List<ColumnRecord> getColumnInputs(List<Map<String, SuggestedMappingDTO>> allMappings) {
        Map<String, Set<String>> valuesByCol = new LinkedHashMap<>();

        for (Map<String, SuggestedMappingDTO> mappingMap : allMappings) {
            if (mappingMap == null) continue;
            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                String colKey = StringUtil.safeTrim(entry.getKey());
                SuggestedMappingDTO mapping = entry.getValue();
                if (colKey.isEmpty() || mapping == null) continue;

                Set<String> vals = valuesByCol.computeIfAbsent(colKey, k -> new LinkedHashSet<>());
                if (mapping.getGroups() != null) {
                    for (SuggestedGroupDTO group : mapping.getGroups()) {
                        if (group == null || group.getValues() == null) continue;
                        for (SuggestedValueDTO v : group.getValues()) {
                            if (v == null) continue;
                            String name = StringUtil.safeTrim(v.getName());
                            if (!name.isEmpty()) vals.add(name);
                        }
                    }
                }
            }
        }

        List<ColumnRecord> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : valuesByCol.entrySet()) {
            out.add(new ColumnRecord(e.getKey(), new ArrayList<>(e.getValue())));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Value-spec extraction
    // ------------------------------------------------------------------

    private List<DescriptionService.ValueSpec> extractValueSpecs(SuggestedMappingDTO mapping) {
        List<DescriptionService.ValueSpec> out = new ArrayList<>();
        if (mapping == null || mapping.getGroups() == null) return out;

        Set<String> seen = new HashSet<>();
        List<DescriptionService.ValueSpec> raw = new ArrayList<>();
        for (SuggestedGroupDTO group : mapping.getGroups()) {
            if (group == null || group.getValues() == null) continue;
            for (SuggestedValueDTO value : group.getValues()) {
                if (value == null) continue;
                String name = StringUtil.safeTrim(value.getName());
                if (name.isEmpty() || !seen.add(name)) continue;
                raw.add(new DescriptionService.ValueSpec(name, extractMinFromValue(value), extractMaxFromValue(value)));
                if (raw.size() >= 10) break;
            }
            if (raw.size() >= 10) break;
        }

        // Compute the scale min and max from the numeric value names so that Python's
        // /describe_batch can anchor ordinal descriptions correctly (e.g. "completely
        // dependent" at 0 and "fully independent" at 10 for a Barthel 0-10 item).
        double scaleMin = Double.POSITIVE_INFINITY;
        double scaleMax = Double.NEGATIVE_INFINITY;
        for (DescriptionService.ValueSpec vs : raw) {
            try {
                double d = Double.parseDouble(vs.v());
                if (d < scaleMin) scaleMin = d;
                if (d > scaleMax) scaleMax = d;
            } catch (NumberFormatException ignored) {}
        }
        boolean hasNumericScale = scaleMin < scaleMax;
        String scaleMinStr = hasNumericScale ? String.valueOf((long) scaleMin) : null;
        String scaleMaxStr = hasNumericScale ? String.valueOf((long) scaleMax) : null;

        for (DescriptionService.ValueSpec vs : raw) {
            try {
                Double.parseDouble(vs.v());
                // Numeric value: use the scale min/max for full context
                out.add(new DescriptionService.ValueSpec(vs.v(), scaleMinStr, scaleMaxStr));
            } catch (NumberFormatException e) {
                // Non-numeric value: keep the interval bounds as-is (may be null)
                out.add(vs);
            }
        }
        return out;
    }

    private String extractMinFromValue(SuggestedValueDTO value) {
        if (value == null || value.getMapping() == null) return null;
        for (org.taniwha.dto.SuggestedRefDTO ref : value.getMapping()) {
            if (ref == null) continue;
            Object v = ref.getValue();
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                Object min = m.get("minValue");
                if (min != null) return String.valueOf(min);
            } else if (v instanceof String s && s.startsWith("min:")) return s.substring(4);
        }
        return null;
    }

    private String extractMaxFromValue(SuggestedValueDTO value) {
        if (value == null || value.getMapping() == null) return null;
        for (org.taniwha.dto.SuggestedRefDTO ref : value.getMapping()) {
            if (ref == null) continue;
            Object v = ref.getValue();
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                Object max = m.get("maxValue");
                if (max != null) return String.valueOf(max);
            } else if (v instanceof String s && s.startsWith("max:")) return s.substring(4);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Terminology resolution helper
    // ------------------------------------------------------------------

    /**
     * Returns the resolved terminology for {@code lookupKey} from Snowstorm results,
     * accepting <em>only</em> valid SNOMED codes.
     *
     * <p>A value is accepted when it matches the Snowstorm output format:
     * {@code "label | numericConceptId"} (e.g. {@code "Diabetes mellitus | 73211009"})
     * or the bare numeric-only form {@code "numericConceptId"}.
     * Synthetic hash codes ({@code CONCEPT_XXXXXXXXX}), raw search phrases, and
     * empty strings are all rejected and {@code ""} is returned, guaranteeing that
     * the {@code terminology} field of every mapping DTO is either a genuine SNOMED
     * code or empty.</p>
     *
     * @param lookupKey the composite key in {@code "colKey|searchTerm"} format
     * @param results   the Snowstorm results map keyed by lookupKey
     * @return a SNOMED label+code string, or {@code ""}
     */
    private static String resolveTerminology(
            String lookupKey,
            Map<String, String> results
    ) {
        String value = results.getOrDefault(lookupKey, "");
        return isSnowstormCode(value) ? value : "";
    }

    /**
     * Returns {@code true} when {@code value} is in the Snowstorm SNOMED format.
     *
     * <p>Accepted formats:
     * <ul>
     *   <li>{@code "label | numericConceptId"} – e.g. {@code "Diabetes mellitus | 73211009"}</li>
     *   <li>{@code "numericConceptId"} – bare code without a label (uncommon but valid)</li>
     * </ul>
     * SNOMED CT concept IDs are at minimum 6 digits; typical codes are 7–18 digits.
     */
    private static boolean isSnowstormCode(String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.trim();
        // New preferred format: "label | code"  (e.g. "Diabetes mellitus | 73211009")
        // Also accept bare numeric code for robustness.
        return v.matches("^.+ \\| \\d{6,}$") || v.matches("^\\d{6,}$");
    }

    // ------------------------------------------------------------------
    // Logging utilities
    // ------------------------------------------------------------------

    private static String shortStr(String s, int maxLen) {
        String x = StringUtil.safeTrim(s).replaceAll("\\s+", " ");
        if (x.length() <= Math.max(1, maxLen)) return x;
        return x.substring(0, Math.max(1, maxLen)) + "...";
    }

    private static <T> List<List<T>> partition(List<T> items, int batchSize) {
        if (items == null || items.isEmpty()) return List.of();
        int bs = Math.max(1, batchSize);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += bs) {
            out.add(items.subList(i, Math.min(items.size(), i + bs)));
        }
        return out;
    }
}
