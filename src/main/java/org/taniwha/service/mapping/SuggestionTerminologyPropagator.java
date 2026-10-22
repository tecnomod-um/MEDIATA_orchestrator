package org.taniwha.service.mapping;

import org.taniwha.dto.ColumnInFileDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.util.StringUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Copies existing source annotations onto suggestions after structural matching is complete. */
final class SuggestionTerminologyPropagator {

    private static final int MIN_AGREEING_SOURCES = 2;
    private static final Pattern SCTID_SUFFIX = Pattern.compile("(?:^|\\||/id/)\\s*(\\d{6,})\\s*$");

    static String conceptId(String terminology) {
        if (terminology == null) return "";
        Matcher matcher = SCTID_SUFFIX.matcher(terminology.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    void propagate(
            List<ColumnInFileDTO> sources,
            List<Map<String, SuggestedMappingDTO>> suggestions
    ) {
        if (sources == null || sources.isEmpty() || suggestions == null || suggestions.isEmpty()) return;

        Map<SourceColumnKey, SourceAnnotations> annotations = indexAnnotations(sources);
        for (Map<String, SuggestedMappingDTO> suggestion : suggestions) {
            if (suggestion == null) continue;
            for (SuggestedMappingDTO mapping : suggestion.values()) {
                propagateMapping(mapping, annotations);
            }
        }
    }

    private void propagateMapping(
            SuggestedMappingDTO mapping,
            Map<SourceColumnKey, SourceAnnotations> annotations
    ) {
        if (mapping == null || mapping.getGroups() == null) return;

        Map<SourceColumnKey, String> columnEvidence = new LinkedHashMap<>();
        for (SuggestedGroupDTO group : mapping.getGroups()) {
            if (group == null || group.getValues() == null) continue;
            for (SuggestedValueDTO value : group.getValues()) {
                if (value == null || value.getMapping() == null) continue;
                for (SuggestedRefDTO ref : value.getMapping()) {
                    SourceColumnKey key = sourceKey(ref);
                    SourceAnnotations source = annotations.get(key);
                    if (source != null && !isBlank(source.columnTerminology())) {
                        columnEvidence.putIfAbsent(key, source.columnTerminology());
                    }
                }
            }
        }

        if (isBlank(mapping.getTerminology())) {
            consensus(columnEvidence).ifPresent(mapping::setTerminology);
        }

        for (SuggestedGroupDTO group : mapping.getGroups()) {
            if (group == null || group.getValues() == null) continue;
            mergeEquivalentValues(group, annotations);
            for (SuggestedValueDTO value : group.getValues()) {
                propagateValue(value, annotations);
            }
        }
    }

    private void mergeEquivalentValues(
            SuggestedGroupDTO group,
            Map<SourceColumnKey, SourceAnnotations> annotations
    ) {
        Map<String, SuggestedValueDTO> firstByConcept = new LinkedHashMap<>();
        List<SuggestedValueDTO> retained = new ArrayList<>();
        for (SuggestedValueDTO value : group.getValues()) {
            String id = valueConceptId(value, annotations);
            SuggestedValueDTO first = id.isEmpty() ? null : firstByConcept.get(id);
            if (first != null && compatibleExistingTerms(first, value, id)
                    && disjointSources(first, value)) {
                List<SuggestedRefDTO> refs = new ArrayList<>(first.getMapping());
                refs.addAll(value.getMapping());
                first.setMapping(refs);
            } else {
                retained.add(value);
                if (!id.isEmpty()) firstByConcept.putIfAbsent(id, value);
            }
        }
        group.setValues(retained);
    }

    private boolean compatibleExistingTerms(SuggestedValueDTO left, SuggestedValueDTO right, String id) {
        String leftId = conceptId(left.getTerminology());
        String rightId = conceptId(right.getTerminology());
        return (leftId.isEmpty() || leftId.equals(id)) && (rightId.isEmpty() || rightId.equals(id));
    }

    private String valueConceptId(
            SuggestedValueDTO value,
            Map<SourceColumnKey, SourceAnnotations> annotations
    ) {
        if (value == null || value.getMapping() == null || value.getMapping().isEmpty()) return "";
        String id = "";
        for (SuggestedRefDTO ref : value.getMapping()) {
            if (!(ref.getValue() instanceof String rawValue)) return "";
            SourceAnnotations source = annotations.get(sourceKey(ref));
            if (source == null) continue;
            String next = conceptId(source.valueTerminologies().get(normalizeValue(rawValue)));
            if (next.isEmpty()) continue;
            if (!id.isEmpty() && !id.equals(next)) return "";
            id = next;
        }
        return id;
    }

    private boolean disjointSources(SuggestedValueDTO left, SuggestedValueDTO right) {
        if (left.getMapping() == null || right.getMapping() == null) return false;
        for (SuggestedRefDTO a : left.getMapping()) {
            for (SuggestedRefDTO b : right.getMapping()) {
                if (sourceKey(a).equals(sourceKey(b))) return false;
            }
        }
        return true;
    }

    private void propagateValue(
            SuggestedValueDTO value,
            Map<SourceColumnKey, SourceAnnotations> annotations
    ) {
        if (value == null || !isBlank(value.getTerminology()) || value.getMapping() == null) return;

        Map<SourceValueKey, String> evidence = new LinkedHashMap<>();
        for (SuggestedRefDTO ref : value.getMapping()) {
            if (!(ref.getValue() instanceof String sourceValue)) continue;
            SourceColumnKey columnKey = sourceKey(ref);
            SourceAnnotations source = annotations.get(columnKey);
            if (source == null) continue;

            String terminology = source.valueTerminologies().get(normalizeValue(sourceValue));
            if (!isBlank(terminology)) {
                evidence.putIfAbsent(new SourceValueKey(columnKey, normalizeValue(sourceValue)), terminology);
            }
        }

        consensus(evidence).ifPresent(value::setTerminology);
    }

    private Map<SourceColumnKey, SourceAnnotations> indexAnnotations(List<ColumnInFileDTO> sources) {
        Map<SourceColumnKey, Map<String, String>> columnEvidence = new LinkedHashMap<>();
        Map<SourceColumnKey, Map<String, Map<String, String>>> valueEvidence = new LinkedHashMap<>();

        for (ColumnInFileDTO source : sources) {
            if (source == null) continue;
            SourceColumnKey sourceKey = new SourceColumnKey(
                    StringUtil.safe(source.getNodeId()),
                    StringUtil.safe(source.getFileName()),
                    StringUtil.safe(source.getColumn())
            );

            addTerm(columnEvidence.computeIfAbsent(sourceKey, ignored -> new LinkedHashMap<>()),
                    source.getTerminology());
            if (source.getValueTerminologies() != null) {
                source.getValueTerminologies().forEach((value, terminology) -> {
                    String normalizedValue = normalizeValue(value);
                    if (!normalizedValue.isEmpty() && !isBlank(terminology)) {
                        Map<String, Map<String, String>> sourceValues =
                                valueEvidence.computeIfAbsent(sourceKey, ignored -> new LinkedHashMap<>());
                        addTerm(sourceValues.computeIfAbsent(normalizedValue, ignored -> new LinkedHashMap<>()),
                                terminology);
                    }
                });
            }
        }

        Map<SourceColumnKey, SourceAnnotations> out = new LinkedHashMap<>();
        for (SourceColumnKey sourceKey : columnEvidence.keySet()) {
            Map<String, String> values = new LinkedHashMap<>();
            valueEvidence.getOrDefault(sourceKey, Map.of()).forEach((value, terms) -> {
                if (terms.size() == 1) values.put(value, terms.values().iterator().next());
            });
            Map<String, String> columnTerms = columnEvidence.get(sourceKey);
            String columnTerm = columnTerms.size() == 1 ? columnTerms.values().iterator().next() : "";
            out.put(sourceKey, new SourceAnnotations(columnTerm, values));
        }
        return out;
    }

    private void addTerm(Map<String, String> termsByConcept, String terminology) {
        String term = trim(terminology);
        String key = conceptKey(term);
        if (!key.isEmpty()) termsByConcept.putIfAbsent(key, term);
    }

    private <K> java.util.Optional<String> consensus(Map<K, String> evidence) {
        if (evidence == null || evidence.size() < MIN_AGREEING_SOURCES) {
            return java.util.Optional.empty();
        }

        Map<String, List<String>> termsByConcept = new LinkedHashMap<>();
        for (String terminology : evidence.values()) {
            String conceptKey = conceptKey(terminology);
            if (!conceptKey.isEmpty()) {
                termsByConcept.computeIfAbsent(conceptKey, ignored -> new ArrayList<>()).add(terminology.trim());
            }
        }

        if (termsByConcept.size() != 1) return java.util.Optional.empty();
        List<String> agreeingTerms = termsByConcept.values().iterator().next();
        if (agreeingTerms.size() < MIN_AGREEING_SOURCES) return java.util.Optional.empty();
        return java.util.Optional.of(agreeingTerms.get(0));
    }

    private String conceptKey(String terminology) {
        String term = trim(terminology);
        if (term.isEmpty()) return "";

        Matcher matcher = SCTID_SUFFIX.matcher(term);
        if (matcher.find()) return "sctid:" + matcher.group(1);

        return "label:" + term.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private SourceColumnKey sourceKey(SuggestedRefDTO ref) {
        if (ref == null) return new SourceColumnKey("", "", "");
        return new SourceColumnKey(
                StringUtil.safe(ref.getNodeId()),
                StringUtil.safe(ref.getFileName()),
                StringUtil.safe(ref.getGroupColumn())
        );
    }

    private String normalizeValue(String value) {
        return trim(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record SourceColumnKey(String nodeId, String fileName, String column) {}

    private record SourceValueKey(SourceColumnKey column, String value) {}

    private record SourceAnnotations(String columnTerminology, Map<String, String> valueTerminologies) {}
}
