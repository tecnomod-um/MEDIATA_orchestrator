package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.service.ValueMappingBuilder;
import org.taniwha.util.StringUtil;

import java.util.*;

/**
 * Assembles {@link SuggestedMappingDTO} instances from embedded column groups and manages
 * union-key uniqueness.
 * <p>
 * Extracted from {@link MappingService} for maintainability.
 * </p>
 */
class MappingAssembler {

    private static final Logger logger = LoggerFactory.getLogger(MappingAssembler.class);

    private final ValueMappingBuilder valueMappingBuilder;

    MappingAssembler(ValueMappingBuilder valueMappingBuilder, MappingServiceSettings settings) {
        this.valueMappingBuilder = valueMappingBuilder;
    }

    // ------------------------------------------------------------------
    // Mapping construction
    // ------------------------------------------------------------------

    /**
     * Builds one mapping entry and appends it to {@code out}.
     * A unique union key derived from {@code baseUnionKey} is registered in {@code usedUnionKeys}.
     */
    void addSuggestedMapping(
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
            values = fallbackValuesForPicked(detectedType, picked);
        }

        group.setValues(values);
        mapping.setGroups(Collections.singletonList(group));
        Map<String, SuggestedMappingDTO> one = new LinkedHashMap<>();
        one.put(unionKey, mapping);
        out.add(one);
    }

    /**
     * Ensures that every column in {@code all} is referenced in at least one mapping in
     * {@code out}; emits a singleton mapping for any column that was missed.
     */
    void ensureAllColumnsCovered(List<EmbeddedColumn> all, List<Map<String, SuggestedMappingDTO>> out) {
        if (all == null || all.isEmpty() || out == null) return;

        Set<String> usedSourceKeys = collectUsedSourceKeys(out);
        Set<String> usedUnionKeys = collectUsedUnionKeys(out);

        int added = 0;
        for (EmbeddedColumn src : all) {
            if (usedSourceKeys.contains(sourceKey(src))) continue;
            emitSingletonMapping(out, usedUnionKeys, src);
            added++;
        }

        if (added > 0) {
            logger.info("[MappingService] full coverage: emitted {} singleton mappings for previously-unmapped columns", added);
        }
    }

    // ------------------------------------------------------------------
    // Type detection
    // ------------------------------------------------------------------

    /**
     * Infers the data type for a mapping from the source columns' parsed statistics, falling
     * back to the schema-declared type when available.
     */
    String detectTypeFromSourcesOrSchema(List<EmbeddedColumn> sources, String schemaType) {
        String st = StringUtil.safeTrim(schemaType).toLowerCase(Locale.ROOT);
        return switch (st) {
            case "integer" -> "integer";
            case "number", "double", "float" -> "double";
            case "date", "datetime" -> "date";
            default -> {
                boolean anyInt = false, anyDouble = false, anyDate = false;
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

    // ------------------------------------------------------------------
    // Key utilities
    // ------------------------------------------------------------------

    /**
     * Converts a raw string to a valid mapping identifier by replacing spaces with {@code _}
     * and stripping non-alphanumeric characters.
     */
    String sanitizeUnionName(String raw) {
        String s = StringUtil.safeTrim(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }

    /** Returns {@code base} if unused, otherwise appends {@code _2}, {@code _3}, … until unique. */
    String makeUnique(String base, Set<String> used) {
        String b = StringUtil.safeTrim(base);
        if (b.isEmpty()) b = "field";
        String out = b;
        int i = 2;
        while (used.contains(out)) { out = b + "_" + i++; }
        used.add(out);
        return out;
    }

    /** Collects all union keys currently present in {@code out}. */
    static Set<String> collectUsedUnionKeys(List<Map<String, SuggestedMappingDTO>> out) {
        Set<String> used = new HashSet<>();
        if (out == null) return used;
        for (Map<String, SuggestedMappingDTO> mm : out) {
            if (mm != null) used.addAll(mm.keySet());
        }
        return used;
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private void emitSingletonMapping(
            List<Map<String, SuggestedMappingDTO>> out,
            Set<String> usedUnionKeys,
            EmbeddedColumn src
    ) {
        if (out == null || usedUnionKeys == null || src == null) return;
        String base = StringUtil.safe(!StringUtil.safe(src.concept).isEmpty() ? src.concept : src.column);
        if (base.trim().isEmpty()) base = "field";
        List<EmbeddedColumn> picked = Collections.singletonList(src);
        addSuggestedMapping(out, usedUnionKeys, base, detectTypeFromSourcesOrSchema(picked, ""), null, picked);
    }

    private List<SuggestedValueDTO> fallbackValuesForPicked(
            String detectedType, List<EmbeddedColumn> picked
    ) {
        String dt = StringUtil.safeTrim(detectedType).toLowerCase(Locale.ROOT);
        if (dt.isEmpty()) {
            dt = StringUtil.safeTrim(detectTypeFromSourcesOrSchema(picked, "")).toLowerCase(Locale.ROOT);
        }
        if (dt.isEmpty()) dt = "value";

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

    private SuggestedMappingDTO buildMappingSkeleton(
            String mappingType, String fileName, List<EmbeddedColumn> pickedCols
    ) {
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

    private static String sourceKey(EmbeddedColumn c) {
        if (c == null) return "";
        return StringUtil.safe(c.nodeId) + "::" + StringUtil.safe(c.fileName) + "::" + StringUtil.safe(c.column);
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
                            used.add(StringUtil.safe(r.getNodeId()) + "::"
                                    + StringUtil.safe(r.getFileName()) + "::"
                                    + StringUtil.safe(r.getGroupColumn()));
                        }
                    }
                }
            }
        }
        return used;
    }
}
