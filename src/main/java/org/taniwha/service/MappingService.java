package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.model.*;
import org.taniwha.util.JsonSchemaParsingUtil;
import org.taniwha.util.MappingMathUtil;
import org.taniwha.util.NormalizationUtil;
import org.taniwha.util.StringUtil;
import org.taniwha.util.ParseUtil;
import org.taniwha.service.jobs.ProgressReporter;

import java.util.*;
import java.util.Objects;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

@Service
public class MappingService {

    private static final Logger logger = LoggerFactory.getLogger(MappingService.class);

    private final EmbeddingService embeddingService;
    private final TerminologyLookupService terminologyLookupService;
    private final TerminologyTermInferenceService terminologyInferenceService;
    private final DescriptionService descriptionGenerator;
    private final ValueMappingBuilder valueMappingBuilder;
    private final ObjectMapper objectMapper;

    private final MappingServiceSettings mappingSettings;

    public MappingService(EmbeddingService embeddingService,
                          TerminologyLookupService terminologyLookupService,
                          TerminologyTermInferenceService terminologyInferenceService,
                          DescriptionService descriptionGenerator,
                          ValueMappingBuilder valueMappingBuilder,
                          ObjectMapper objectMapper,
                          MappingServiceSettings mappingSettings) {
        this.embeddingService = embeddingService;
        this.terminologyLookupService = terminologyLookupService;
        this.terminologyInferenceService = terminologyInferenceService;
        this.descriptionGenerator = descriptionGenerator;
        this.valueMappingBuilder = valueMappingBuilder;
        this.objectMapper = objectMapper;

        this.mappingSettings = mappingSettings;
        logger.info("[MappingService] Initialized.");
    }

    // Creates suggested mappings for columns and their values
    public List<Map<String, SuggestedMappingDTO>> suggestMappings(MappingSuggestRequestDTO req) {

        List<ColumnInFileDTO> columnDTOs = (req == null || req.getElementFiles() == null) ? Collections.emptyList() : req.getElementFiles();
        logger.info("[TRACE] suggestMappings: {} element files", columnDTOs.size());
        if (columnDTOs.isEmpty()) return Collections.emptyList();

        List<String> affectedColumnNames = new ArrayList<>(columnDTOs.size());
        for (ColumnInFileDTO dto : columnDTOs) {
            if (dto == null) continue;
            String colName = StringUtil.safeTrim(dto.getColumn());
            if (!colName.isEmpty()) {
                affectedColumnNames.add(colName);
            }
        }

        // Structural noise to avoid
        LearnedNoise learnedNoise = learnNoiseFromRequest(affectedColumnNames);
        List<EmbeddedColumn> processedColumns = new ArrayList<>(columnDTOs.size());

        columnDTOs.stream().filter(Objects::nonNull).forEach(c -> {
            String nodeId = StringUtil.safe(c.getNodeId());
            String fileName = StringUtil.safe(c.getFileName());
            String colName = StringUtil.safe(c.getColumn());
            List<String> rawValues = c.getValues();
            ColStats stats = ParseUtil.parseColStats(rawValues);

            String concept = normalizeRawColumn(colName, learnedNoise);
            float[] combined = embeddingService.embedColumnWithValues(concept, rawValues);
            if (combined == null || combined.length == 0) {
                logger.warn("[MappingService] embed failed for '{}' (skipping column)", colName);
                return;
            }
            processedColumns.add(new EmbeddedColumn(nodeId, fileName, colName, concept, rawValues, combined, stats));
        });
        if (processedColumns.isEmpty()) return Collections.emptyList();

        List<EmbeddedSchemaField> schemaFields = embedSchemaFields(req.getSchema());
        List<Map<String, SuggestedMappingDTO>> suggestionsOutput = schemaFields.isEmpty() ?
                suggestWithoutSchema(processedColumns) : suggestWithSchema(schemaFields, processedColumns);

        ensureAllColumnsCovered(processedColumns, suggestionsOutput);
        return suggestionsOutput;
    }

    private LearnedNoise learnNoiseFromRequest(List<String> rawColumnNames) {
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> suffixCount = new HashMap<>();

        int n = 0;
        for (String rawColumnName : (rawColumnNames == null ? List.<String>of() : rawColumnNames)) {
            String name = StringUtil.safeTrim(rawColumnName);
            if (name.isEmpty()) continue;
            List<String> tokens = tokenizeName(name);
            if (tokens.isEmpty()) continue;
            n++;

            Set<String> uniq = new HashSet<>(tokens);
            for (String t : uniq) {
                if (t == null || t.isEmpty()) continue;
                df.put(t, df.getOrDefault(t, 0) + 1);
            }

            String last = tokens.get(tokens.size() - 1);
            if (last != null && !last.isEmpty()) {
                suffixCount.put(last, suffixCount.getOrDefault(last, 0) + 1);
            }
        }
        Set<String> globalCandidates = new HashSet<>();
        Set<String> suffixCandidates = new HashSet<>();

        for (Map.Entry<String, Integer> e : df.entrySet()) {
            String t = e.getKey();
            int c = e.getValue();
            if (c >= mappingSettings.noiseDocumentFrequencyMinCount()) globalCandidates.add(t);
            else if (n > 0 && (c / (double) n) >= mappingSettings.noiseDocumentFrequencyFraction()) globalCandidates.add(t);
        }

        for (Map.Entry<String, Integer> e : suffixCount.entrySet()) {
            if (e.getValue() >= mappingSettings.suffixNoiseMinCount()) suffixCandidates.add(e.getKey());
        }

        Set<String> globalStop = new HashSet<>();
        for (String t : globalCandidates) {
            if (isStructuralToken(t)) globalStop.add(t);
        }

        Set<String> suffixStop = new HashSet<>();
        for (String t : suffixCandidates) {
            if (isStructuralSuffixToken(t)) suffixStop.add(t);
        }

        if (n <= 6 && globalStop.size() > 3) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>();
            for (String t : globalStop) {
                entries.add(Map.entry(t, df.getOrDefault(t, 0)));
            }
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            Set<String> keep = new HashSet<>();
            for (int i = 0; i < Math.min(3, entries.size()); i++) keep.add(entries.get(i).getKey());
            globalStop.retainAll(keep);
        }

        return new LearnedNoise(globalStop, suffixStop, df, n);
    }

    private List<Map<String, SuggestedMappingDTO>> suggestWithSchema(
            List<EmbeddedSchemaField> schemaFields,
            List<EmbeddedColumn> allColumns
    ) {
        Map<String, List<SimilarityScore<EmbeddedColumn>>> colsBySchema = new HashMap<>();

        for (EmbeddedColumn src : allColumns) {
            SimilarityScore<EmbeddedSchemaField> best = bestSchemaMatch(src, schemaFields);
            if (best == null || best.sim() < mappingSettings.schemaToColumnThreshold()) continue;

            colsBySchema
                    .computeIfAbsent(best.item().name, k -> new ArrayList<>())
                    .add(new SimilarityScore<>(src, best.sim()));
        }

        Set<String> usedUnionKeys = new HashSet<>();
        List<Map<String, SuggestedMappingDTO>> out = new ArrayList<>();

        for (EmbeddedSchemaField field : schemaFields) {
            List<SimilarityScore<EmbeddedColumn>> candidates = colsBySchema.get(field.name);
            if (candidates == null || candidates.isEmpty()) continue;

            candidates.sort((a, b) -> Double.compare(b.sim(), a.sim()));
            String detectedFieldType = detectTypeFromSourcesOrSchema(
                    topCols(candidates),
                    StringUtil.safe(field.type)
            );

            int emitted = 0;
            for (List<SimilarityScore<EmbeddedColumn>> batch : partitionTopCandidates(candidates)) {
                if (emitted >= mappingSettings.maxSuggestionsPerSchemaField()) break;

                List<EmbeddedColumn> picked = pickBestPerFileFromScores(batch);
                if (picked.isEmpty()) continue;

                addSuggestedMapping(out, usedUnionKeys, field.name, detectedFieldType, field, picked);
                emitted++;
            }
        }
        return out;
    }

    private List<Map<String, SuggestedMappingDTO>> suggestWithoutSchema(List<EmbeddedColumn> allColumns) {
        List<ColCluster> clusters = clusterColumns(allColumns);

        clusters.sort((a, b) -> {
            int as = a.cols.size(), bs = b.cols.size();
            if (as != bs) return Integer.compare(bs, as);
            return a.representativeConcept.compareToIgnoreCase(b.representativeConcept);
        });

        if (clusters.size() > mappingSettings.maxSchemalessTargets()) {
            clusters = clusters.subList(0, mappingSettings.maxSchemalessTargets());
        }

        Set<String> usedUnionKeys = new HashSet<>();
        List<Map<String, SuggestedMappingDTO>> out = new ArrayList<>();

        for (ColCluster cl : clusters) {
            if (cl.cols.isEmpty()) continue;

            String concept = StringUtil.safe(cl.representativeConcept);
            if (concept.trim().isEmpty()) continue;

            List<EmbeddedColumn> picked = pickBestPerFileFromCols(new ArrayList<>(cl.cols), cl.centroid, mappingSettings.maxColumnsPerMapping());
            if (picked.isEmpty()) continue;

            String detectedType = detectTypeFromSourcesOrSchema(picked, "");
            addSuggestedMapping(out, usedUnionKeys, concept, detectedType, null, picked);
        }

        return out;
    }

    private void addSuggestedMapping(
            List<Map<String, SuggestedMappingDTO>> out,
            Set<String> usedUnionKeys,
            String baseUnionKey,
            String detectedType,
            EmbeddedSchemaField schemaFieldOrNull,
            List<EmbeddedColumn> picked
    ) {
        if (out == null || usedUnionKeys == null || picked == null || picked.isEmpty()) return;

        String base = sanitizeUnionName(StringUtil.safe(baseUnionKey));
        if (base.isEmpty()) base = "field";
        String unionKey = makeUnique(base, usedUnionKeys);

        SuggestedMappingDTO mapping = buildMappingSkeleton("standard", "suggested_mapping", picked);

        SuggestedGroupDTO group = new SuggestedGroupDTO();
        group.setColumn(unionKey);

        List<SuggestedValueDTO> values =
                valueMappingBuilder.buildValuesForConcept(unionKey, detectedType, schemaFieldOrNull, picked);

        if (values == null || values.isEmpty()) {
            values = fallbackValuesForPicked(unionKey, detectedType, picked);
        }

        group.setValues(values);
        mapping.setGroups(Collections.singletonList(group));
        Map<String, SuggestedMappingDTO> one = new LinkedHashMap<>();
        one.put(unionKey, mapping);
        out.add(one);
    }

    // Fetch metadata for mappings
    public List<Map<String, SuggestedMappingDTO>> enrichHierarchy(
            List<Map<String, SuggestedMappingDTO>> hierarchy,
            String schema,
            ProgressReporter progress
    ) {
        if (hierarchy == null || hierarchy.isEmpty()) return Collections.emptyList();
        populateTerminologyAndDescriptionsBatch(hierarchy, progress == null ? ProgressReporter.noop() : progress);
        ensureNonEmptyMappingDescriptions(hierarchy);
        return hierarchy;
    }

    private void ensureNonEmptyMappingDescriptions(List<Map<String, SuggestedMappingDTO>> allMappings) {
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

    private void populateTerminologyAndDescriptionsBatch(
            List<Map<String, SuggestedMappingDTO>> allMappings,
            ProgressReporter cb
    ){
        if (allMappings == null || allMappings.isEmpty()) return;
        if (cb != null) cb.report(5, "Inferring terminology search terms…");
        List<ColumnRecord> colInputs = getColumnInputs(allMappings);
        int inferBatchSize = terminologyInferenceService == null ? 0 : terminologyInferenceService.batchSize();
        if (inferBatchSize <= 0) inferBatchSize = 5;

        Map<String, String> colLookupKeyByCol = new LinkedHashMap<>();
        Map<String, String> valLookupKeyByValueKey = new LinkedHashMap<>();

        int inferredCols = 0;

        int totalInferBatches = colInputs.isEmpty()
                ? 0
                : ((colInputs.size() + inferBatchSize - 1) / inferBatchSize);

        int inferBatchNo = 0;

        // ---------------------------
        // 1) Terminology inference (batched) + progress
        // ---------------------------
        for (int i = 0; i < colInputs.size(); i += inferBatchSize) {
            int end = Math.min(colInputs.size(), i + inferBatchSize);
            List<ColumnRecord> batch = colInputs.subList(i, end);

            inferBatchNo++;
            if (cb != null) {
                // 5..29 while running batches
                double frac = (inferBatchNo - 1) / (double) Math.max(1, totalInferBatches);
                int pct = 5 + (int) Math.floor(frac * 25.0);
                pct = Math.max(5, Math.min(29, pct));
                cb.report(pct, "Inferring terminology… (batch " + inferBatchNo + "/" + totalInferBatches + ")");
            }

            List<TerminologyTermInferenceService.InferredTerm> inferred =
                    terminologyInferenceService == null ? List.of() : terminologyInferenceService.inferBatch(batch);

            if (inferred.isEmpty()) {
                // fallback: use raw colKey/value as lookup phrase
                for (ColumnRecord ci : batch) {
                    if (ci == null) continue;
                    String colKey = StringUtil.safeTrim(ci.colKey());
                    if (colKey.isEmpty()) continue;

                    String lookupKey = colKey + "|" + colKey;
                    colLookupKeyByCol.put(colKey, lookupKey);

                    if (ci.values() != null) {
                        for (String rv : ci.values()) {
                            String v = StringUtil.safeTrim(rv);
                            if (v.isEmpty()) continue;
                            String valueKey = colKey + "|" + v;
                            String vLookupKey = colKey + "|" + v;
                            valLookupKeyByValueKey.put(valueKey, vLookupKey);
                        }
                    }
                }

                logger.info("[TermInfer] batch {}/{} -> inferred {}", inferBatchNo, Math.max(1, totalInferBatches), 0);
                continue;
            }

            for (TerminologyTermInferenceService.InferredTerm it : inferred) {
                if (it == null) continue;
                String colKey = StringUtil.safeTrim(it.colKey());
                if (colKey.isEmpty()) continue;

                String colSearchTerm = StringUtil.safeTrim(it.colSearchTerm());
                if (colSearchTerm.isEmpty()) colSearchTerm = colKey;

                String colLookupKey = colKey + "|" + colSearchTerm;
                colLookupKeyByCol.put(colKey, colLookupKey);

                Map<String, String> vmap = it.valueSearchTerms() == null ? Map.of() : it.valueSearchTerms();
                for (Map.Entry<String, String> ve : vmap.entrySet()) {
                    String rawVal = StringUtil.safeTrim(ve.getKey());
                    String vTerm = StringUtil.safeTrim(ve.getValue());
                    if (rawVal.isEmpty() || vTerm.isEmpty()) continue;

                    String valueKey = colKey + "|" + rawVal;
                    String vLookupKey = colKey + "|" + vTerm;
                    valLookupKeyByValueKey.put(valueKey, vLookupKey);
                }

                inferredCols++;
            }

            logger.info("[TermInfer] batch {}/{} -> inferred {}",
                    inferBatchNo, Math.max(1, totalInferBatches), inferred.size());

            // Include what was inferred (bounded)
            if (logger.isInfoEnabled()) {
                logger.info("[TermInfer] inferred detail batch {}/{}: {}",
                        inferBatchNo, Math.max(1, totalInferBatches),
                        summarizeInferred(inferred, 4, 2, 90));
            }

            // end-of-batch inference progress (5..30)
            if (cb != null) {
                double fracDone = inferBatchNo / (double) Math.max(1, totalInferBatches);
                int pct = 5 + (int) Math.floor(fracDone * 25.0);
                pct = Math.max(5, Math.min(30, pct));
                cb.report(pct, "Inferring terminology… (" + inferBatchNo + "/" + totalInferBatches + ")");
            }
        }

        logger.info("[MappingService] terminology inference: cols={}, inferred={}", colInputs.size(), inferredCols);

        if (cb != null) cb.report(30, "Terminology inference complete.");
        if (cb != null) cb.report(35, "Looking up SNOMED codes…");

        List<TerminologyLookupService.TerminologyRequest> requests = new ArrayList<>();
        Set<String> seenReq = new HashSet<>();

        for (Map.Entry<String, String> e : colLookupKeyByCol.entrySet()) {
            String lookupKey = e.getValue();
            if (!StringUtil.safeTrim(lookupKey).isEmpty() && seenReq.add(lookupKey)) {
                requests.add(new TerminologyLookupService.TerminologyRequest(lookupKey, null));
            }
        }
        for (Map.Entry<String, String> e : valLookupKeyByValueKey.entrySet()) {
            String valueKey = e.getKey();
            String lookupKey = e.getValue();
            String colKey = "";
            int bar = valueKey.indexOf('|');
            if (bar > 0) colKey = valueKey.substring(0, bar);

            if (!StringUtil.safeTrim(lookupKey).isEmpty() && seenReq.add(lookupKey)) {
                requests.add(new TerminologyLookupService.TerminologyRequest(lookupKey, colKey));
            }
        }

        logger.info("[MappingService] SNOMED lookup requests={}", requests.size());

        Map<String, String> terminologyResults =
                terminologyLookupService == null ? Map.of() : terminologyLookupService.batchLookupTerminology(requests);

        // Retry missing once (as before)
        int missing = 0;
        List<TerminologyLookupService.TerminologyRequest> retryReqs = new ArrayList<>();
        for (TerminologyLookupService.TerminologyRequest r : requests) {
            if (r == null) continue;
            String k = StringUtil.safeTrim(r.term);
            if (k.isEmpty()) continue;
            if (!terminologyResults.containsKey(k)) {
                missing++;
                retryReqs.add(r);
            }
        }

        if (missing > 0 && terminologyLookupService != null) {
            logger.warn("[MappingService] SNOMED missing {} keys, retrying once", missing);
            Map<String, String> retry = terminologyLookupService.batchLookupTerminology(retryReqs);
            if (retry != null && !retry.isEmpty()) {
                Map<String, String> merged = new LinkedHashMap<>(terminologyResults);
                merged.putAll(retry);
                terminologyResults = merged;
            }
        }

        logger.info("[MappingService] SNOMED lookup done: returned={}", terminologyResults.size());
        if (cb != null) cb.report(60, "SNOMED lookup complete. Applying terminologies…");

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
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .orElse("field");

                // Column terminology
                String colLookupKey = colLookupKeyByCol.getOrDefault(colKey, colKey + "|" + colKey);
                String columnTerminology = terminologyResults.getOrDefault(colLookupKey, "");
                mapping.setTerminology(columnTerminology == null ? "" : columnTerminology);

                // Value terminology
                if (mapping.getGroups() != null) {
                    for (SuggestedGroupDTO group : mapping.getGroups()) {
                        if (group == null || group.getValues() == null) continue;
                        for (SuggestedValueDTO value : group.getValues()) {
                            if (value == null) continue;
                            String valueName = StringUtil.safeTrim(value.getName());
                            if (valueName.isEmpty()) continue;

                            String valueKey = colKey + "|" + valueName;
                            String vLookupKey = valLookupKeyByValueKey.get(valueKey);
                            String valueTerminology =
                                    (vLookupKey == null) ? "" : terminologyResults.getOrDefault(vLookupKey, "");
                            value.setTerminology(valueTerminology == null ? "" : valueTerminology);
                        }
                    }
                }

                // Prepare description tasks
                if (colCount < mappingSettings.maxColumnDescTasks()) {
                    List<DescriptionService.ValueSpec> specs = extractValueSpecs(mapping);
                    tasks.add(new EnrichTask(colKey, mapping, specs));
                    colCount++;
                } else {
                    // Hard cap safety fallback
                    if (mapping.getDescription() == null || mapping.getDescription().trim().isEmpty()) {
                        mapping.setDescription(colKey);
                    }
                    if (mapping.getGroups() != null) {
                        for (SuggestedGroupDTO group : mapping.getGroups()) {
                            if (group == null || group.getValues() == null) continue;
                            for (SuggestedValueDTO value : group.getValues()) {
                                if (value == null) continue;
                                String valueName = StringUtil.safeTrim(value.getName());
                                if (value.getDescription() == null || value.getDescription().trim().isEmpty()) {
                                    value.setDescription(valueName.isEmpty() ? "value" : valueName);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (cb != null) cb.report(65, "Terminologies applied. Generating descriptions…");
        final int totalColsToDescribe = tasks.size();

        IntConsumer reportProgress = doneCols -> {
            if (cb == null) return;
            if (totalColsToDescribe == 0) {
                cb.report(100, "Done.");
                return;
            }
            int pct = 65 + (int) Math.floor((doneCols / (double) totalColsToDescribe) * 35.0);
            pct = Math.max(65, Math.min(99, pct));
            cb.report(pct, "Generating descriptions… (" + doneCols + "/" + totalColsToDescribe + ")");
        };

        final AtomicInteger doneCols = new AtomicInteger(0);

        Consumer<List<CompletableFuture<Void>>> flushWindow = window -> {
            if (window.isEmpty()) return;
            CompletableFuture.allOf(window.toArray(new CompletableFuture[0])).join();
            window.clear();
        };

        List<List<EnrichTask>> descBatches = partition(tasks, Math.max(1, mappingSettings.descriptionBatchColumns()));
        List<CompletableFuture<Void>> window = new ArrayList<>(mappingSettings.descriptionConcurrencyWindow());

        int batchNo = 0;
        for (List<EnrichTask> batch : descBatches) {
            batchNo++;
            final int thisBatchNo = batchNo;

            List<DescriptionService.ColumnEnrichmentInput> inputs = new ArrayList<>(batch.size());
            for (EnrichTask t : batch) {
                if (t == null || t.mapping == null) continue;
                inputs.add(new DescriptionService.ColumnEnrichmentInput(
                        t.colKey,
                        StringUtil.safe(t.mapping.getTerminology()),
                        t.valueSpecs
                ));
            }
            if (inputs.isEmpty()) continue;

            window.add(
                    descriptionGenerator
                            .generateEnrichmentBatchAsync(inputs)
                            .thenAccept(results -> {
                                for (EnrichTask t : batch) {
                                    if (t == null || t.mapping == null) continue;

                                    DescriptionService.EnrichmentResult r =
                                            (results == null) ? null : results.get(t.colKey);

                                    SuggestedMappingDTO mapping = t.mapping;

                                    if (r != null) {
                                        String cd = r.colDesc();
                                        if (cd != null && !cd.trim().isEmpty()) {
                                            mapping.setDescription(cd);
                                        } else if (mapping.getDescription() == null || mapping.getDescription().trim().isEmpty()) {
                                            mapping.setDescription(t.colKey);
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
                                        // fallback
                                        if (mapping.getDescription() == null || mapping.getDescription().trim().isEmpty()) {
                                            mapping.setDescription(t.colKey);
                                        }
                                        if (mapping.getGroups() != null) {
                                            for (SuggestedGroupDTO group : mapping.getGroups()) {
                                                if (group == null || group.getValues() == null) continue;
                                                for (SuggestedValueDTO value : group.getValues()) {
                                                    if (value == null) continue;
                                                    String vn = StringUtil.safeTrim(value.getName());
                                                    if (vn.isEmpty()) continue;
                                                    if (value.getDescription() == null || value.getDescription().trim().isEmpty())
                                                        value.setDescription(vn);
                                                }
                                            }
                                        }
                                    }

                                    int done = doneCols.incrementAndGet();
                                    reportProgress.accept(done);
                                }

                                if (thisBatchNo <= 3) {
                                    logger.info("[DESC-BATCH] completed batch {}/{} (cols={})",
                                            thisBatchNo, descBatches.size(), batch.size());
                                }
                            })
                            .exceptionally(ex -> {
                                logger.warn("[DESC-BATCH] failed batch {}/{} ({})",
                                        thisBatchNo, descBatches.size(), ex.toString());

                                // fallback for entire batch
                                for (EnrichTask t : batch) {
                                    if (t == null || t.mapping == null) continue;
                                    SuggestedMappingDTO mapping = t.mapping;

                                    if (mapping.getDescription() == null || mapping.getDescription().trim().isEmpty()) {
                                        mapping.setDescription(t.colKey);
                                    }
                                    if (mapping.getGroups() != null) {
                                        for (SuggestedGroupDTO group : mapping.getGroups()) {
                                            if (group == null || group.getValues() == null) continue;
                                            for (SuggestedValueDTO value : group.getValues()) {
                                                if (value == null) continue;
                                                String vn = StringUtil.safeTrim(value.getName());
                                                if (vn.isEmpty()) continue;
                                                if (value.getDescription() == null || value.getDescription().trim().isEmpty())
                                                    value.setDescription(vn);
                                            }
                                        }
                                    }

                                    int done = doneCols.incrementAndGet();
                                    reportProgress.accept(done);
                                }
                                return null;
                            })
            );

            if (window.size() >= mappingSettings.descriptionConcurrencyWindow()) flushWindow.accept(window);
        }

        flushWindow.accept(window);

        if (cb != null) cb.report(100, "Done.");
    }

    private static List<ColumnRecord> getColumnInputs(List<Map<String, SuggestedMappingDTO>> allMappings) {
        List<ColumnRecord> colInputs = new ArrayList<>();
        Map<String, Set<String>> valuesByCol = new LinkedHashMap<>();

        for (Map<String, SuggestedMappingDTO> mappingMap : allMappings) {
            if (mappingMap == null) continue;

            for (Map.Entry<String, SuggestedMappingDTO> entry : mappingMap.entrySet()) {
                String colKeyRaw = entry.getKey();
                SuggestedMappingDTO mapping = entry.getValue();
                if (mapping == null) continue;

                String colKey = StringUtil.safeTrim(colKeyRaw);
                if (colKey.isEmpty()) continue;

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

        for (Map.Entry<String, Set<String>> e : valuesByCol.entrySet()) {
            colInputs.add(new ColumnRecord(e.getKey(), new ArrayList<>(e.getValue())));
        }
        return colInputs;
    }

    private static String summarizeInferred(List<TerminologyTermInferenceService.InferredTerm> inferred,
                                            int maxCols,
                                            int maxValsPerCol,
                                            int maxLen) {
        if (inferred == null || inferred.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(512);
        sb.append("[");
        int cols = 0;

        for (TerminologyTermInferenceService.InferredTerm it : inferred) {
            if (it == null) continue;
            if (cols++ >= Math.max(1, maxCols)) break;

            String ck = shortStr(it.colKey(), maxLen);
            String ct = shortStr(it.colSearchTerm(), maxLen);

            sb.append("{colKey=").append(ck)
                    .append(", colSearchTerm=").append(ct);

            Map<String, String> vm = (it.valueSearchTerms() == null) ? Map.of() : it.valueSearchTerms();
            if (!vm.isEmpty()) {
                sb.append(", values=[");
                int v = 0;
                for (Map.Entry<String, String> e : vm.entrySet()) {
                    if (v++ >= Math.max(1, maxValsPerCol)) break;
                    sb.append("{raw=").append(shortStr(e.getKey(), maxLen))
                            .append(", term=").append(shortStr(e.getValue(), maxLen))
                            .append("},");
                }
                if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
                sb.append("]");
            }

            sb.append("},");
        }

        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        if (inferred.size() > cols) sb.append(", …");
        sb.append("]");
        return sb.toString();
    }

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

    private List<DescriptionService.ValueSpec> extractValueSpecs(SuggestedMappingDTO mapping) {
        List<DescriptionService.ValueSpec> out = new ArrayList<>();
        if (mapping == null || mapping.getGroups() == null) return out;

        Set<String> seen = new HashSet<>();
        for (SuggestedGroupDTO group : mapping.getGroups()) {
            if (group == null || group.getValues() == null) continue;
            for (SuggestedValueDTO value : group.getValues()) {
                if (value == null) continue;
                String name = StringUtil.safeTrim(value.getName());
                if (name.isEmpty() || !seen.add(name)) continue;
                String min = extractMinFromValue(value);
                String max = extractMaxFromValue(value);
                out.add(new DescriptionService.ValueSpec(name, min, max));
                if (out.size() >= 10) return out;
            }
        }
        return out;
    }

    private String extractMinFromValue(SuggestedValueDTO value) {
        if (value == null || value.getMapping() == null) return null;

        for (SuggestedRefDTO ref : value.getMapping()) {
            if (ref == null) continue;
            Object v = ref.getValue();
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) v;
                Object min = m.get("minValue");
                if (min != null) return String.valueOf(min);
            } else if (v instanceof String s) {
                if (s.startsWith("min:")) return s.substring(4);
            }
        }
        return null;
    }

    private String extractMaxFromValue(SuggestedValueDTO value) {
        if (value == null || value.getMapping() == null) return null;

        for (SuggestedRefDTO ref : value.getMapping()) {
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

    private List<EmbeddedSchemaField> embedSchemaFields(String schemaJson) {
        List<JsonSchemaParsingUtil.SchemaFieldDef> defs =
                JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, schemaJson, mappingSettings.maxEnum());

        if (defs.isEmpty()) return Collections.emptyList();

        List<EmbeddedSchemaField> out = new ArrayList<>();
        for (JsonSchemaParsingUtil.SchemaFieldDef d : defs) {
            String fieldName = d.getName();
            String type = d.getType();
            List<String> enumVals = d.getEnumValues();

            float[] combined = embeddingService.embedSchemaField(fieldName, type, enumVals);
            out.add(new EmbeddedSchemaField(fieldName, type, enumVals, combined));
        }
        return out;
    }

    private List<String> tokenizeName(String rawColumn) {
        String s0 = StringUtil.safeTrim(rawColumn);
        if (s0.isEmpty()) return Collections.emptyList();

        String s = NormalizationUtil.splitCamelStrong(s0);
        s = s.toLowerCase(Locale.ROOT);

        // Split alpha<->digit boundaries before non-alnum split, so "bart2" becomes "bart 2"
        s = s.replaceAll("([a-z])([0-9])", "$1 $2");
        s = s.replaceAll("([0-9])([a-z])", "$1 $2");

        String[] parts = s.split("[^a-z0-9]+");
        List<String> toks = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;

            // drop pure digits
            if (t.matches("\\d+")) continue;

            // drop single-letter tokens (y/n, etc.) - structural noise, not clinical meaning
            if (t.length() == 1) continue;

            toks.add(t);
        }

        return toks;
    }

    private boolean isStructuralToken(String t) {
        if (t == null) return false;
        String x = t.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return false;

        if (x.matches("\\d+")) return true;
        if (x.length() == 1) return true;

        if (x.length() == 2) {
            return !containsVowel(x);
        }

        return x.equals("id")
                || x.equals("code")
                || x.equals("name")
                || x.equals("value")
                || x.equals("flag")
                || x.equals("indicator")
                || x.equals("status")
                || x.equals("type");
    }

    private boolean isStructuralSuffixToken(String t) {
        if (t == null) return false;
        String x = t.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return false;

        if (x.matches("\\d+")) return true;
        if (x.length() == 1) return true;

        // If suffix is short and vowel-less, it's likely structural (dataset marker)
        return x.length() <= 4 && !containsVowel(x);
        // If suffix is long and looks like a normal word (has vowels), keep it (likely meaningful)
    }

    private boolean containsVowel(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') return true;
        }
        return false;
    }

    private String normalizeRawColumn(String rawColumn, LearnedNoise noise) {
        List<String> tokens = tokenizeName(rawColumn);
        if (tokens.isEmpty()) return "";
        List<String> kept = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);

            if (noise != null) {
                if (noise.globalStopTokens().contains(t)) {
                    logger.debug("[Noise] Removed token '{}' from column '{}' (structural global)", t, rawColumn);
                    continue;
                }
                if (i == tokens.size() - 1 && noise.suffixStopTokens().contains(t)) {
                    logger.debug("[Noise] Removed token '{}' from column '{}' (structural suffix)", t, rawColumn);
                    continue;
                }
            }
            kept.add(t);
        }
        if (kept.isEmpty()) kept = tokens;
        return String.join(" ", kept).replaceAll("\\s+", " ").trim();
    }

    private List<List<SimilarityScore<EmbeddedColumn>>> partitionTopCandidates(List<SimilarityScore<EmbeddedColumn>> candidates) {
        int take = Math.min(80, candidates.size());
        List<SimilarityScore<EmbeddedColumn>> top = new ArrayList<>(candidates.subList(0, take));
        List<List<SimilarityScore<EmbeddedColumn>>> out = new ArrayList<>();
        out.add(top);
        return out;
    }

    private void ensureAllColumnsCovered(List<EmbeddedColumn> all, List<Map<String, SuggestedMappingDTO>> out) {
        if (all == null || all.isEmpty()) return;
        if (out == null) return;

        Set<String> usedSourceKeys = collectUsedSourceKeys(out);
        Set<String> usedUnionKeys = collectUsedUnionKeys(out);

        int added = 0;
        for (EmbeddedColumn src : all) {
            String sk = sourceKey(src);
            if (usedSourceKeys.contains(sk)) continue;

            emitSingletonMapping(out, usedUnionKeys, src);
            added++;
        }

        if (added > 0) {
            logger.info("[MappingService] full coverage: emitted {} singleton mappings for previously-unmapped columns", added);
        }
    }

    private void emitSingletonMapping(List<Map<String, SuggestedMappingDTO>> out, Set<String> usedUnionKeys, EmbeddedColumn src) {
        if (out == null || usedUnionKeys == null || src == null) return;

        String base = StringUtil.safe(!StringUtil.safe(src.concept).isEmpty() ? src.concept : src.column);
        if (base.trim().isEmpty()) base = "field";

        List<EmbeddedColumn> picked = Collections.singletonList(src);
        String detectedType = detectTypeFromSourcesOrSchema(picked, "");
        addSuggestedMapping(out, usedUnionKeys, base, detectedType, null, picked);
    }

    private List<SuggestedValueDTO> fallbackValuesForPicked(String unionKey, String detectedType, List<EmbeddedColumn> picked) {
        String dt = StringUtil.safeTrim(detectedType).toLowerCase(Locale.ROOT);
        if (dt.isEmpty()) {
            dt = detectTypeFromSourcesOrSchema(picked, "");
            dt = StringUtil.safeTrim(dt).toLowerCase(Locale.ROOT);
        }
        if (dt.isEmpty()) dt = "value";

        // Minimal bucket: one SuggestedValueDTO with refs to each source column
        SuggestedValueDTO v = new SuggestedValueDTO();
        v.setName(dt);
        List<SuggestedRefDTO> refs = new ArrayList<>();

        if (picked != null) {
            for (EmbeddedColumn src : picked) {
                if (src == null) continue;
                SuggestedRefDTO ref = new SuggestedRefDTO();
                ref.setNodeId(src.nodeId);
                ref.setFileName(src.fileName);
                ref.setGroupColumn(src.column);
                ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                ref.setValue(dt);
                refs.add(ref);
            }
        }
        v.setMapping(refs);
        return Collections.singletonList(v);
    }

    private static String sourceKey(EmbeddedColumn c) {
        if (c == null) return "";
        return StringUtil.safe(c.nodeId) + "::" + StringUtil.safe(c.fileName) + "::" + StringUtil.safe(c.column);
    }

    private static Set<String> collectUsedUnionKeys(List<Map<String, SuggestedMappingDTO>> out) {
        Set<String> used = new HashSet<>();
        if (out == null) return used;
        for (Map<String, SuggestedMappingDTO> mm : out) {
            if (mm == null) continue;
            used.addAll(mm.keySet());
        }
        return used;
    }

    private static Set<String> collectUsedSourceKeys(List<Map<String, SuggestedMappingDTO>> out) {
        Set<String> used = new HashSet<>();
        if (out == null) return used;

        for (Map<String, SuggestedMappingDTO> mm : out) {
            if (mm == null) continue;

            for (SuggestedMappingDTO m : mm.values()) {
                if (m == null || m.getGroups() == null) continue;

                for (SuggestedGroupDTO g : m.getGroups()) {
                    if (g == null || g.getValues() == null) continue;

                    for (SuggestedValueDTO v : g.getValues()) {
                        if (v == null || v.getMapping() == null) continue;

                        for (SuggestedRefDTO r : v.getMapping()) {
                            if (r == null) continue;
                            String k = StringUtil.safe(r.getNodeId()) + "::" + StringUtil.safe(r.getFileName()) + "::" + StringUtil.safe(r.getGroupColumn());
                            used.add(k);
                        }
                    }
                }
            }
        }
        return used;
    }

    private List<EmbeddedColumn> pickBestPerFileFromScores(List<SimilarityScore<EmbeddedColumn>> scored) {
        Map<String, SimilarityScore<EmbeddedColumn>> best = new LinkedHashMap<>();

        for (SimilarityScore<EmbeddedColumn> s : scored) {
            EmbeddedColumn col = s.item();
            String fk = col.fileKey();

            SimilarityScore<EmbeddedColumn> cur = best.get(fk);
            if (cur == null || s.sim() > cur.sim()) {
                best.put(fk, s);
            }
        }

        List<SimilarityScore<EmbeddedColumn>> list = new ArrayList<>(best.values());
        list.sort((a, b) -> Double.compare(b.sim(), a.sim()));
        List<EmbeddedColumn> out = new ArrayList<>();
        for (SimilarityScore<EmbeddedColumn> s : list) {
            if (out.size() >= mappingSettings.maxColumnsPerMapping()) break;
            out.add(s.item());
        }
        return out;
    }

    private List<EmbeddedColumn> pickBestPerFileFromCols(List<EmbeddedColumn> cols, float[] centroid, int cap) {
        Map<String, EmbeddedColumn> best = new LinkedHashMap<>();
        Map<String, Double> bestSim = new HashMap<>();

        for (EmbeddedColumn c : cols) {
            String fk = c.fileKey();
            double sim = (centroid == null) ? 0.0 : MappingMathUtil.cosine(c.vec, centroid);

            EmbeddedColumn cur = best.get(fk);
            if (cur == null) {
                best.put(fk, c);
                bestSim.put(fk, sim);
                continue;
            }

            double curSim = bestSim.getOrDefault(fk, -1.0);
            if (sim > curSim + 1e-9) {
                best.put(fk, c);
                bestSim.put(fk, sim);
            } else if (Math.abs(sim - curSim) <= 1e-9) {
                String a = StringUtil.safe(c.column);
                String b = StringUtil.safe(cur.column);
                if (a.length() < b.length() || (a.length() == b.length() && a.compareToIgnoreCase(b) < 0)) {
                    best.put(fk, c);
                    bestSim.put(fk, sim);
                }
            }
        }

        return getEmbeddedColumns(cap, best, bestSim);
    }

    private List<EmbeddedColumn> getEmbeddedColumns(int cap, Map<String, EmbeddedColumn> best, Map<String, Double> bestSim) {
        List<EmbeddedColumn> out = new ArrayList<>(best.values());
        out.sort((a, b) -> {
            double sa = bestSim.getOrDefault(a.fileKey(), 0.0);
            double sb = bestSim.getOrDefault(b.fileKey(), 0.0);
            int x = Double.compare(sb, sa);
            if (x != 0) return x;
            x = a.fileName.compareToIgnoreCase(b.fileName);
            if (x != 0) return x;
            return a.column.compareToIgnoreCase(b.column);
        });

        if (out.size() > cap) out = out.subList(0, cap);
        return out;
    }

    private String detectTypeFromSourcesOrSchema(List<EmbeddedColumn> sources, String schemaType) {
        String st = StringUtil.safeTrim(schemaType).toLowerCase(Locale.ROOT);

        return switch (st) {
            case "integer" -> "integer";
            case "number", "double", "float" -> "double";
            case "date", "datetime" -> "date";
            default -> {
                boolean anyInt = false;
                boolean anyDouble = false;
                boolean anyDate = false;

                if (sources != null) {
                    for (EmbeddedColumn s : sources) {
                        if (s == null || s.stats == null) continue;
                        if (s.stats.hasIntegerMarker) anyInt = true;
                        if (s.stats.hasDoubleMarker) anyDouble = true;
                        if (s.stats.hasDateMarker) anyDate = true;
                    }
                }
                if (anyDate) yield "date";
                if (anyDouble) yield "double";
                if (anyInt) yield "integer";
                yield "";
            }
        };
    }

    private List<EmbeddedColumn> topCols(List<SimilarityScore<EmbeddedColumn>> cs) {
        List<EmbeddedColumn> out = new ArrayList<>();
        int lim = Math.min(30, cs.size());
        for (int i = 0; i < lim; i++)
            out.add(cs.get(i).item());
        return out;
    }

    private SimilarityScore<EmbeddedSchemaField> bestSchemaMatch(EmbeddedColumn src, List<EmbeddedSchemaField> fields) {
        EmbeddedSchemaField best = null;
        double bestSim = -1.0;

        for (EmbeddedSchemaField f : fields) {
            double sim = MappingMathUtil.cosine(src.vec, f.vec);
            if (sim > bestSim) {
                bestSim = sim;
                best = f;
            }
        }

        if (best == null) return null;
        return new SimilarityScore<>(best, bestSim);
    }

    private List<ColCluster> clusterColumns(List<EmbeddedColumn> all) {
        Map<String, ColCluster> byConcept = new LinkedHashMap<>();
        for (EmbeddedColumn c : all) {
            String key = sanitizeUnionName(StringUtil.safeTrim(c.concept).toLowerCase(Locale.ROOT));
            if (key.isEmpty()) key = "__empty__";
            ColCluster cl = byConcept.get(key);
            if (cl == null) {
                cl = new ColCluster();
                byConcept.put(key, cl);
            }
            cl.add(c);
        }

        List<ColCluster> clusters = new ArrayList<>(byConcept.values());
        List<ColCluster> merged = new ArrayList<>();

        for (ColCluster col : clusters) {
            String colConcept = col.cols.isEmpty() ? "" : col.cols.get(0).concept;

            // Track the best unconditional target (sim >= 0.80 or sim >= threshold + Jaccard)
            ColCluster bestUnconditional = null;
            double bestUnconditionalSim = -1;

            // Track the best abbreviation target (sim >= threshold + abbreviation detection),
            // checked against every column in each candidate cluster — not just the first.
            ColCluster bestAbbreviation = null;
            double bestAbbreviationSim = -1;

            for (ColCluster cl : merged) {
                double sim = MappingMathUtil.cosine(col.centroid, cl.centroid);
                double jac = conceptTokenJaccard(col, cl);

                if ((sim >= 0.80 || (sim >= mappingSettings.columnClusterThreshold() && jac >= 0.15))
                        && sim > bestUnconditionalSim) {
                    bestUnconditionalSim = sim;
                    bestUnconditional = cl;
                }

                if (sim >= mappingSettings.columnClusterThreshold() && sim > bestAbbreviationSim) {
                    for (EmbeddedColumn clCol : cl.cols) {
                        if (isAbbreviationPair(colConcept, clCol.concept)) {
                            bestAbbreviationSim = sim;
                            bestAbbreviation = cl;
                            break;
                        }
                    }
                }
            }

            // Prefer a traditional match; fall back to an abbreviation-based match
            ColCluster target = (bestUnconditional != null) ? bestUnconditional : bestAbbreviation;
            if (target != null) {
                for (EmbeddedColumn r : col.cols) target.add(r);
            } else {
                merged.add(col);
            }
        }

        for (ColCluster c : merged) {
            c.representativeConcept = pickClusterRepresentativeConcept(c.cols);
        }

        return merged;
    }

    private double conceptTokenJaccard(ColCluster a, ColCluster b) {
        Set<String> sa = conceptTokens(a.representativeConcept);
        Set<String> sb = conceptTokens(b.representativeConcept);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;

        int inter = 0;
        for (String x : sa) if (sb.contains(x)) inter++;
        int union = sa.size() + sb.size() - inter;
        if (union <= 0) return 0.0;
        return inter / (double) union;
    }

    private Set<String> conceptTokens(String concept) {
        String s = StringUtil.safeTrim(concept).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return Collections.emptySet();
        String[] parts = s.split("[^a-z0-9]+");
        Set<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d+")) continue;
            if (t.length() <= 1) continue;
            out.add(t);
        }
        return out;
    }

    /**
     * Returns true if one concept appears to be an abbreviation or acronym of the other.
     * Handles three cases:
     *   1. Initialism (order-insensitive): "sbp" ↔ "blood pressure systolic" ({s,b,p} are all initials)
     *   2. Prefix: "cr" ↔ "serum creatinine" ("cr" is a prefix of "creatinine")
     *   3. Suffix-initialism: "egfr" ↔ "glomerular filtration rate" (suffix "gfr" has all initials)
     * The embedding-similarity gate in the caller prevents false positives from unrelated pairs
     * that happen to share initials.
     */
    private boolean isAbbreviationPair(String conceptA, String conceptB) {
        if (conceptA == null || conceptB == null) return false;
        return looksLikeAbbreviationOf(conceptA, conceptB)
                || looksLikeAbbreviationOf(conceptB, conceptA);
    }

    private boolean looksLikeAbbreviationOf(String candidate, String fullConcept) {
        String[] candidateTokens = candidate.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        String[] fullTokensArr = fullConcept.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");

        // Full concept must have at least 2 meaningful tokens
        List<String> fullParts = new ArrayList<>();
        for (String t : fullTokensArr) { if (t != null && !t.isEmpty()) fullParts.add(t); }
        if (fullParts.size() < 2) return false;

        // Build initials multiset: char → how many full-concept tokens start with that char
        // (e.g., "Low Density Lipoprotein" → {l:2, d:1} because both L and l start two words)
        Map<Character, Integer> initialsMultiset = new LinkedHashMap<>();
        for (String token : fullParts) initialsMultiset.merge(token.charAt(0), 1, Integer::sum);

        // Try each candidate token as a potential abbreviation (2–6 chars)
        for (String abbrev : candidateTokens) {
            if (abbrev == null || abbrev.isEmpty()) continue;
            if (abbrev.length() < 2 || abbrev.length() > 6) continue;

            // Build multiset of abbreviation characters
            Map<Character, Integer> abbrevMultiset = new LinkedHashMap<>();
            for (char c : abbrev.toCharArray()) abbrevMultiset.merge(c, 1, Integer::sum);

            // Case 1: Initialism — every char in the abbreviation must appear at least as often
            // in the initials multiset. Using multiset prevents LDL from matching HDL's initials
            // {h,d,l} even though both share {d,l}.
            boolean initialsMatch = true;
            for (Map.Entry<Character, Integer> e : abbrevMultiset.entrySet()) {
                if (initialsMultiset.getOrDefault(e.getKey(), 0) < e.getValue()) {
                    initialsMatch = false;
                    break;
                }
            }
            if (initialsMatch) return true;

            // Case 2: Prefix — abbreviation is a STRICT prefix of a full-concept token
            // (token must be strictly longer to avoid "score".startsWith("score") false positives)
            for (String token : fullParts) {
                if (token.length() > abbrev.length() && token.startsWith(abbrev)) return true;
            }

            // Case 3: Suffix-initialism — for prefixed abbreviations like eGFR → GFR → GlomerularFiltrationRate
            // Only runs for abbreviations of length ≥ 4 (loop starts at 1 and needs suffix length ≥ 3,
            // i.e., abbrev.length() - start ≥ 3 → start ≤ abbrev.length() - 3).
            // 3-char abbreviations like "gfr" are correctly handled by Case 1 (initialism) alone.
            for (int start = 1; start <= abbrev.length() - 3; start++) {
                String suffix = abbrev.substring(start); // length ≥ 3 because start ≤ abbrev.length()-3
                Map<Character, Integer> suffixMultiset = new LinkedHashMap<>();
                for (char c : suffix.toCharArray()) suffixMultiset.merge(c, 1, Integer::sum);
                boolean suffixMatch = true;
                for (Map.Entry<Character, Integer> e : suffixMultiset.entrySet()) {
                    if (initialsMultiset.getOrDefault(e.getKey(), 0) < e.getValue()) {
                        suffixMatch = false;
                        break;
                    }
                }
                if (suffixMatch) return true;
            }
        }
        return false;
    }

    private String pickClusterRepresentativeConcept(List<EmbeddedColumn> cols) {
        String best = null;
        int bestLen = Integer.MAX_VALUE;

        for (EmbeddedColumn c : cols) {
            String name = StringUtil.safeTrim(c.concept);
            if (name.isEmpty()) continue;

            int len = name.length();
            if (len < bestLen) { best = name; bestLen = len; }
            else if (len == bestLen && best != null) {
                if (name.compareToIgnoreCase(best) < 0) best = name;
            }
        }

        if (best == null) best = cols.get(0).concept;
        return best;
    }

    private SuggestedMappingDTO buildMappingSkeleton(String mappingType, String fileName, List<EmbeddedColumn> pickedCols) {
        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        mapping.setMappingType(mappingType);
        mapping.setFileName(fileName);
        mapping.setNodeId("");
        mapping.setTerminology("");
        mapping.setDescription("");
        mapping.setColumns(extractSourceColumnNames(pickedCols));
        return mapping;
    }

    private List<String> extractSourceColumnNames(List<EmbeddedColumn> cols) {
        List<String> out = new ArrayList<>();
        for (EmbeddedColumn c : cols) out.add(c.column);
        return out;
    }

    private String sanitizeUnionName(String raw) {
        String s = StringUtil.safeTrim(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }

    private String makeUnique(String base, Set<String> used) {
        String b = StringUtil.safeTrim(base);
        if (b.isEmpty()) b = "field";

        String out = b;
        int i = 2;
        while (used.contains(out)) {
            out = b + "_" + i;
            i++;
        }
        used.add(out);
        return out;
    }
}
