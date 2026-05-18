package org.taniwha.service.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.model.ColCluster;
import org.taniwha.model.ColStats;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.model.LearnedNoise;
import org.taniwha.model.SimilarityScore;
import org.taniwha.service.EmbeddingService;
import org.taniwha.service.enrichment.DescriptionService;
import org.taniwha.service.enrichment.OpenMedTerminologyService;
import org.taniwha.service.enrichment.TerminologyLookupService;
import org.taniwha.service.ValueMappingBuilder;
import org.taniwha.service.jobs.ProgressReporter;
import org.taniwha.util.JsonSchemaParsingUtil;
import org.taniwha.util.ParseUtil;
import org.taniwha.util.StringUtil;
import org.taniwha.util.ValueVectorUtil;

import java.util.*;

@Service
// Orchestrates column normalization, clustering, mapping assembly, and enrichment.
public class MappingService {

    private static final Logger logger = LoggerFactory.getLogger(MappingService.class);

    private static final Set<String> CONTEXT_TOKEN_EXCLUSIONS = Set.of(
            "admission", "admit", "discharge", "assessment", "visit", "birth", "death",
            "date", "time", "year", "years", "age"
    );

    private static final Set<String> GENERIC_CONTEXT_REMAINDERS = Set.of(
            "total", "tot", "sum", "score", "scores", "index", "value", "values",
            "status", "state", "type", "code", "flag", "indicator"
    );

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final MappingServiceSettings mappingSettings;

    private final ColumnNormalizer columnNormalizer;
    private final ColumnClusterer columnClusterer;
    private final ColumnPicker columnPicker;
    private final MappingAssembler mappingAssembler;
    private final MappingEnrichmentHelper enrichmentHelper;
    private final SchemaColumnMatchGuard schemaColumnMatchGuard;

    public MappingService(EmbeddingService embeddingService,
                          TerminologyLookupService terminologyLookupService,
                          OpenMedTerminologyService openMedTerminologyService,
                          DescriptionService descriptionGenerator,
                          ValueMappingBuilder valueMappingBuilder,
                          ObjectMapper objectMapper,
                          MappingServiceSettings mappingSettings) {
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.mappingSettings = mappingSettings;

        this.columnNormalizer  = new ColumnNormalizer(mappingSettings);
        this.columnClusterer   = new ColumnClusterer(mappingSettings);
        this.columnPicker      = new ColumnPicker();
        this.mappingAssembler  = new MappingAssembler(valueMappingBuilder);
        this.schemaColumnMatchGuard = new SchemaColumnMatchGuard();
        this.enrichmentHelper  = new MappingEnrichmentHelper(
                openMedTerminologyService,
                terminologyLookupService, descriptionGenerator, mappingSettings);

        logger.info("[MappingService] Initialized.");
    }

    public List<Map<String, SuggestedMappingDTO>> suggestMappings(MappingSuggestRequestDTO req) {
        List<ColumnInFileDTO> columnDTOs = (req == null || req.getElementFiles() == null)
                ? Collections.emptyList() : req.getElementFiles();
        logger.info("[TRACE] suggestMappings: {} element files", columnDTOs.size());
        if (columnDTOs.isEmpty()) return Collections.emptyList();

        List<String> affectedColumnNames = new ArrayList<>(columnDTOs.size());
        for (ColumnInFileDTO dto : columnDTOs) {
            if (dto == null) continue;
            String colName = StringUtil.safeTrim(dto.getColumn());
            if (!colName.isEmpty()) affectedColumnNames.add(colName);
        }

        LearnedNoise learnedNoise = columnNormalizer.learnNoiseFromRequest(affectedColumnNames);
        List<EmbeddedColumn> processedColumns = new ArrayList<>(columnDTOs.size());

        columnDTOs.stream().filter(Objects::nonNull).forEach(c -> {
            String nodeId    = StringUtil.safe(c.getNodeId());
            String fileName  = StringUtil.safe(c.getFileName());
            String colName   = StringUtil.safe(c.getColumn());
            List<String> rawValues = c.getValues();
            ColStats stats   = ParseUtil.parseColStats(rawValues);

            String concept   = columnNormalizer.normalizeRawColumn(colName, learnedNoise);
            float[] combined = embeddingService.embedColumnWithValues(concept, rawValues);
            if (combined == null || combined.length == 0) {
                logger.warn("[MappingService] embed failed for '{}' (skipping column)", colName);
                return;
            }
            // Value vectors stay separate so domain similarity does not distort the main semantic embedding.
            float[] valueVec = ValueVectorUtil.build(rawValues);
            processedColumns.add(new EmbeddedColumn(nodeId, fileName, colName, concept, rawValues, combined, valueVec, stats));
        });
        if (processedColumns.isEmpty()) return Collections.emptyList();

        List<EmbeddedSchemaField> schemaFields = embedSchemaFields(req.getSchema());
        List<Map<String, SuggestedMappingDTO>> out;
        if (schemaFields.isEmpty()) {
            out = suggestWithoutSchema(processedColumns);
            mappingAssembler.ensureAllColumnsCovered(processedColumns, out);
        } else {
            out = suggestWithSchema(schemaFields, processedColumns);
        }
        return out;
    }

    public List<Map<String, SuggestedMappingDTO>> enrichHierarchy(
            List<Map<String, SuggestedMappingDTO>> hierarchy,
            String schema,
            ProgressReporter progress
    ) {
        if (hierarchy == null || hierarchy.isEmpty()) return Collections.emptyList();
        ProgressReporter cb = progress == null ? ProgressReporter.noop() : progress;
        enrichmentHelper.populateTerminologyAndDescriptionsBatch(hierarchy, cb);
        enrichmentHelper.ensureNonEmptyMappingDescriptions(hierarchy);
        return hierarchy;
    }

    private List<Map<String, SuggestedMappingDTO>> suggestWithSchema(
            List<EmbeddedSchemaField> schemaFields,
            List<EmbeddedColumn> allColumns
    ) {
        Set<String> sourceContextTokens = learnLeadingContextTokens(allColumns);
        List<EmbeddedColumn> schemaColumns = stripLeadingContextFromColumns(allColumns, sourceContextTokens);
        Map<String, List<SimilarityScore<EmbeddedColumn>>> colsBySchema = new HashMap<>();

        for (EmbeddedColumn src : schemaColumns) {
            SimilarityScore<EmbeddedSchemaField> best =
                    bestSchemaMatchWithStructuralEvidence(src, schemaFields, sourceContextTokens);
            if (best == null) continue;
            colsBySchema.computeIfAbsent(best.item().name, k -> new ArrayList<>())
                    .add(new SimilarityScore<>(src, best.sim()));
        }

        Set<String> usedUnionKeys = new HashSet<>();
        List<Map<String, SuggestedMappingDTO>> out = new ArrayList<>();

        for (EmbeddedSchemaField field : schemaFields) {
            List<SimilarityScore<EmbeddedColumn>> candidates = colsBySchema.get(field.name);
            if (candidates == null || candidates.isEmpty()) continue;

            candidates.sort((a, b) -> Double.compare(b.sim(), a.sim()));
            List<EmbeddedColumn> picked = columnPicker.topCols(candidates);
            String detectedType = mappingAssembler.detectTypeFromSourcesOrSchema(picked, StringUtil.safe(field.type));

            mappingAssembler.addSuggestedMapping(out, usedUnionKeys, field.name, detectedType, field, picked);
        }
        return out;
    }

    private SimilarityScore<EmbeddedSchemaField> bestSchemaMatchWithStructuralEvidence(
            EmbeddedColumn src,
            List<EmbeddedSchemaField> schemaFields,
            Set<String> sourceContextTokens
    ) {
        EmbeddedSchemaField bestField = null;
        double bestSim = -1.0;
        int bestQuality = 0;

        for (EmbeddedSchemaField field : schemaFields) {
            double sim = org.taniwha.util.MappingMathUtil.cosine(src.vec, field.vec);
            int quality = schemaColumnMatchGuard.matchQuality(field, src, sim, sourceContextTokens);
            if (quality <= 0) continue;

            if (quality > bestQuality || (quality == bestQuality && sim > bestSim)) {
                bestQuality = quality;
                bestSim = sim;
                bestField = field;
            }
        }

        if (bestField == null) return null;
        return new SimilarityScore<>(bestField, bestSim);
    }

    private Set<String> learnLeadingContextTokens(List<EmbeddedColumn> columns) {
        Map<String, Set<String>> followers = new HashMap<>();
        if (columns == null) return Collections.emptySet();

        for (EmbeddedColumn col : columns) {
            List<String> tokens = tokenizeForContext(col == null ? "" : col.column);
            if (tokens.size() < 2) continue;
            followers.computeIfAbsent(tokens.get(0), k -> new HashSet<>()).add(tokens.get(1));
        }

        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : followers.entrySet()) {
            String token = e.getKey();
            if (token.length() < 3) continue;
            if (CONTEXT_TOKEN_EXCLUSIONS.contains(token)) continue;
            if (e.getValue().size() >= 2) out.add(token);
        }
        return out;
    }

    private List<String> tokenizeForContext(String raw) {
        String prepared = StringUtil.safe(raw)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ");
        List<String> out = new ArrayList<>();
        for (String part : prepared.split("\\s+")) {
            String token = part.trim();
            if (token.isEmpty() || token.matches("\\d+") || token.length() == 1) continue;
            out.add(token);
        }
        return out;
    }

    private List<Map<String, SuggestedMappingDTO>> suggestWithoutSchema(List<EmbeddedColumn> allColumns) {
        List<EmbeddedColumn> normalizedColumns = stripLeadingContextFromColumns(allColumns, learnLeadingContextTokens(allColumns));
        List<ColCluster> clusters = columnClusterer.clusterColumns(normalizedColumns);

        clusters.sort((a, b) -> {
            int diff = Integer.compare(b.cols.size(), a.cols.size());
            if (diff != 0) return diff;
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

            List<List<EmbeddedColumn>> batches =
                    columnPicker.partitionMembersByFile(new ArrayList<>(cl.cols), cl.centroid);

            int emitted = 0;
            for (List<EmbeddedColumn> batch : batches) {
                if (emitted >= mappingSettings.maxSuggestionsPerSchemaField()) break;
                if (batch.isEmpty()) continue;
                String detectedType = mappingAssembler.detectTypeFromSourcesOrSchema(batch, "");
                mappingAssembler.addSuggestedMapping(out, usedUnionKeys, concept, detectedType, null, batch);
                emitted++;
            }
        }
        return out;
    }

    private List<EmbeddedColumn> stripLeadingContextFromColumns(List<EmbeddedColumn> columns, Set<String> contextTokens) {
        if (columns == null || columns.isEmpty() || contextTokens == null || contextTokens.isEmpty()) {
            return columns == null ? Collections.emptyList() : columns;
        }

        List<EmbeddedColumn> out = new ArrayList<>(columns.size());
        for (EmbeddedColumn col : columns) {
            if (col == null) continue;
            String concept = stripLeadingContext(col.concept, contextTokens);
            float[] vec = concept.equals(StringUtil.safe(col.concept))
                    ? col.vec
                    : embeddingService.embedColumnWithValues(concept, col.rawValues);
            out.add(new EmbeddedColumn(
                    col.nodeId,
                    col.fileName,
                    col.column,
                    concept,
                    col.rawValues,
                    vec,
                    col.valueVec,
                    col.stats
            ));
        }
        return out;
    }

    private String stripLeadingContext(String concept, Set<String> contextTokens) {
        List<String> tokens = tokenizeForContext(concept);
        if (tokens.size() < 2 || !contextTokens.contains(tokens.get(0))) {
            return StringUtil.safe(concept);
        }
        List<String> remaining = tokens.subList(1, tokens.size());
        if (remaining.stream().allMatch(GENERIC_CONTEXT_REMAINDERS::contains)) {
            return StringUtil.safe(concept);
        }
        return String.join(" ", remaining);
    }

    private List<EmbeddedSchemaField> embedSchemaFields(String schemaJson) {
        List<JsonSchemaParsingUtil.SchemaFieldDef> defs =
                JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, schemaJson, mappingSettings.maxEnum());
        if (defs.isEmpty()) return Collections.emptyList();

        List<EmbeddedSchemaField> out = new ArrayList<>();
        for (JsonSchemaParsingUtil.SchemaFieldDef d : defs) {
            float[] combined = embeddingService.embedSchemaField(
                    d.getName(), d.getType(), d.getEnumValues(), d.getDescription());
            out.add(new EmbeddedSchemaField(d.getName(), d.getType(), d.getDescription(), d.getEnumValues(), combined));
        }
        return out;
    }
}
