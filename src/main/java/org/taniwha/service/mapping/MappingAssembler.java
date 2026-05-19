package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.service.ValueMappingBuilder;
import org.taniwha.util.StringUtil;

import java.util.*;

// Assembles suggested mapping DTOs from detected concepts, types, and source columns.
class MappingAssembler {

    private static final Logger logger = LoggerFactory.getLogger(MappingAssembler.class);

    private final ValueMappingBuilder valueMappingBuilder;

    MappingAssembler(ValueMappingBuilder valueMappingBuilder) {
        this.valueMappingBuilder = valueMappingBuilder;
    }

    void addSuggestedMapping(
            List<Map<String, SuggestedMappingDTO>> out,
            Set<String> usedUnionKeys,
            String baseUnionKey,
            String detectedType,
            EmbeddedSchemaField schemaFieldOrNull,
            List<EmbeddedColumn> picked
    ) {
        if (out == null || usedUnionKeys == null || picked == null || picked.isEmpty()) return;
        picked = keepOneColumnPerSourceFile(picked);
        if (picked.isEmpty()) return;

        String baseLabel = StringUtil.safe(baseUnionKey);
        if (schemaFieldOrNull == null) {
            String sourceColumnLabel = sharedSourceColumnLabel(picked);
            if (!sourceColumnLabel.isEmpty()) {
                baseLabel = sourceColumnLabel;
            }
        }

        String base = sanitizeUnionName(baseLabel);
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

    void ensureAllColumnsCovered(List<EmbeddedColumn> all, List<Map<String, SuggestedMappingDTO>> out) {
        if (all == null || all.isEmpty() || out == null) return;

        // Coverage is tracked from emitted source refs rather than top-level mapping keys.
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

    String detectTypeFromSourcesOrSchema(List<EmbeddedColumn> sources, String schemaType) {
        String st = StringUtil.safeTrim(schemaType).toLowerCase(Locale.ROOT);
        return switch (st) {
            case "integer" -> "integer";
            case "number", "double", "float" -> "double";
            case "date", "datetime" -> "date";
            case "string", "boolean" -> "string";
            default -> {
                boolean anyInt = false, anyDouble = false, anyDate = false;
                if (sources != null) {
                    for (EmbeddedColumn s : sources) {
                        if (s == null || s.stats == null) continue;
                        if (s.stats.isHasIntegerMarker()) anyInt = true;
                        // Any explicit floating marker should widen the merged type to double.
                        if (s.stats.isHasDoubleMarker()) anyDouble = true;
                        if (s.stats.isHasDateMarker()) anyDate = true;
                    }
                }
                if (anyDate) yield "date";
                if (anyDouble) yield "double";
                if (anyInt) yield "integer";
                yield "";
            }
        };
    }

    String sanitizeUnionName(String raw) {
        String s = StringUtil.safeTrim(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }

    String makeUnique(String base, Set<String> used) {
        String b = StringUtil.safeTrim(base);
        if (b.isEmpty()) b = "field";
        String out = b;
        int i = 2;
        while (used.contains(out)) { out = b + "_" + i++; }
        used.add(out);
        return out;
    }

    private String sharedSourceColumnLabel(List<EmbeddedColumn> picked) {
        if (picked == null || picked.isEmpty()) return "";
        String first = StringUtil.safeTrim(picked.get(0).column);
        if (first.isEmpty()) return "";
        if (picked.size() == 1) return first;

        String firstKey = first.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT);
        for (EmbeddedColumn col : picked) {
            String next = StringUtil.safeTrim(col == null ? "" : col.column);
            String nextKey = next.replaceAll("[^A-Za-z0-9]+", "").toLowerCase(Locale.ROOT);
            if (!firstKey.equals(nextKey)) return "";
        }
        return first;
    }

    static Set<String> collectUsedUnionKeys(List<Map<String, SuggestedMappingDTO>> out) {
        Set<String> used = new HashSet<>();
        if (out == null) return used;
        for (Map<String, SuggestedMappingDTO> mm : out) {
            if (mm != null) used.addAll(mm.keySet());
        }
        return used;
    }

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

    private List<EmbeddedColumn> keepOneColumnPerSourceFile(List<EmbeddedColumn> cols) {
        if (cols == null || cols.isEmpty()) return Collections.emptyList();
        List<EmbeddedColumn> out = new ArrayList<>();
        Set<String> seenFiles = new HashSet<>();
        for (EmbeddedColumn c : cols) {
            if (c == null) continue;
            if (seenFiles.add(c.fileKey())) {
                out.add(c);
            }
        }
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
