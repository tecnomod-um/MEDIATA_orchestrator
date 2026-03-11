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
import org.taniwha.service.DescriptionService;
import org.taniwha.service.EmbeddingService;
import org.taniwha.service.OpenMedTerminologyService;
import org.taniwha.service.TerminologyLookupService;
import org.taniwha.service.TerminologyTermInferenceService;
import org.taniwha.service.ValueMappingBuilder;
import org.taniwha.service.jobs.ProgressReporter;
import org.taniwha.util.JsonSchemaParsingUtil;
import org.taniwha.util.ParseUtil;
import org.taniwha.util.StringUtil;
import org.taniwha.util.ValueVectorUtil;

import java.util.*;

/**
 * Orchestrates column-concept matching and mapping suggestion.
 *
 * <p>This class is intentionally kept as a thin coordinator. All heavy logic has been
 * extracted into focused helpers in this package:</p>
 * <ul>
 *   <li>{@link ColumnNormalizer}  — tokenisation, noise learning, concept normalisation</li>
 *   <li>{@link ColumnClusterer}   — cosine-similarity clustering with structural guards</li>
 *   <li>{@link ColumnPicker}      — score-based column selection and batch partitioning</li>
 *   <li>{@link MappingAssembler}  — DTO assembly, union-key management, type detection</li>
 *   <li>{@link MappingEnrichmentHelper} — terminology lookup and description generation</li>
 * </ul>
 */
@Service
public class MappingService {

    private static final Logger logger = LoggerFactory.getLogger(MappingService.class);

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final MappingServiceSettings mappingSettings;

    // Package-private helpers — instantiated from the same injectable dependencies.
    private final ColumnNormalizer columnNormalizer;
    private final ColumnClusterer columnClusterer;
    private final ColumnPicker columnPicker;
    private final MappingAssembler mappingAssembler;
    private final MappingEnrichmentHelper enrichmentHelper;

    public MappingService(EmbeddingService embeddingService,
                          TerminologyLookupService terminologyLookupService,
                          TerminologyTermInferenceService terminologyInferenceService,
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
        this.columnPicker      = new ColumnPicker(mappingSettings);
        this.mappingAssembler  = new MappingAssembler(valueMappingBuilder, mappingSettings);
        this.enrichmentHelper  = new MappingEnrichmentHelper(
                openMedTerminologyService, terminologyInferenceService,
                terminologyLookupService, descriptionGenerator, mappingSettings);

        logger.info("[MappingService] Initialized.");
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Creates suggested mappings for the columns and their values in the request. */
    public List<Map<String, SuggestedMappingDTO>> suggestMappings(MappingSuggestRequestDTO req) {
        List<ColumnInFileDTO> columnDTOs = (req == null || req.getElementFiles() == null)
                ? Collections.emptyList() : req.getElementFiles();
        logger.info("[TRACE] suggestMappings: {} element files", columnDTOs.size());
        if (columnDTOs.isEmpty()) return Collections.emptyList();

        // Build the column-name list used for noise learning.
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
            // Compute a char-n-gram value vector for categorical columns so that the clusterer
            // can use value-level character similarity as structural alignment evidence.
            float[] valueVec = ValueVectorUtil.build(rawValues);
            processedColumns.add(new EmbeddedColumn(nodeId, fileName, colName, concept, rawValues, combined, valueVec, stats));
        });
        if (processedColumns.isEmpty()) return Collections.emptyList();

        List<EmbeddedSchemaField> schemaFields = embedSchemaFields(req.getSchema());
        List<Map<String, SuggestedMappingDTO>> out = schemaFields.isEmpty()
                ? suggestWithoutSchema(processedColumns)
                : suggestWithSchema(schemaFields, processedColumns);

        mappingAssembler.ensureAllColumnsCovered(processedColumns, out);
        return out;
    }

    /** Enriches an already-suggested hierarchy with terminology codes and descriptions. */
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

    // ------------------------------------------------------------------
    // Schema-guided matching
    // ------------------------------------------------------------------

    private List<Map<String, SuggestedMappingDTO>> suggestWithSchema(
            List<EmbeddedSchemaField> schemaFields,
            List<EmbeddedColumn> allColumns
    ) {
        Map<String, List<SimilarityScore<EmbeddedColumn>>> colsBySchema = new HashMap<>();

        for (EmbeddedColumn src : allColumns) {
            SimilarityScore<EmbeddedSchemaField> best = columnPicker.bestSchemaMatch(src, schemaFields);
            if (best == null || best.sim() < mappingSettings.schemaToColumnThreshold()) continue;
            colsBySchema.computeIfAbsent(best.item().name, k -> new ArrayList<>())
                    .add(new SimilarityScore<>(src, best.sim()));
        }

        Set<String> usedUnionKeys = new HashSet<>();
        List<Map<String, SuggestedMappingDTO>> out = new ArrayList<>();

        for (EmbeddedSchemaField field : schemaFields) {
            List<SimilarityScore<EmbeddedColumn>> candidates = colsBySchema.get(field.name);
            if (candidates == null || candidates.isEmpty()) continue;

            candidates.sort((a, b) -> Double.compare(b.sim(), a.sim()));
            String detectedType = mappingAssembler.detectTypeFromSourcesOrSchema(
                    columnPicker.topCols(candidates), StringUtil.safe(field.type));

            List<List<EmbeddedColumn>> batches =
                    columnPicker.partitionMembersByFile(columnPicker.topCols(candidates), null);

            int emitted = 0;
            for (List<EmbeddedColumn> batch : batches) {
                if (emitted >= mappingSettings.maxSuggestionsPerSchemaField()) break;
                if (batch.isEmpty()) continue;
                mappingAssembler.addSuggestedMapping(out, usedUnionKeys, field.name, detectedType, field, batch);
                emitted++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Schema-free clustering
    // ------------------------------------------------------------------

    private List<Map<String, SuggestedMappingDTO>> suggestWithoutSchema(List<EmbeddedColumn> allColumns) {
        List<ColCluster> clusters = columnClusterer.clusterColumns(allColumns);

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

    // ------------------------------------------------------------------
    // Schema embedding
    // ------------------------------------------------------------------

    private List<EmbeddedSchemaField> embedSchemaFields(String schemaJson) {
        List<JsonSchemaParsingUtil.SchemaFieldDef> defs =
                JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, schemaJson, mappingSettings.maxEnum());
        if (defs.isEmpty()) return Collections.emptyList();

        List<EmbeddedSchemaField> out = new ArrayList<>();
        for (JsonSchemaParsingUtil.SchemaFieldDef d : defs) {
            float[] combined = embeddingService.embedSchemaField(d.getName(), d.getType(), d.getEnumValues());
            out.add(new EmbeddedSchemaField(d.getName(), d.getType(), d.getEnumValues(), combined));
        }
        return out;
    }
}
