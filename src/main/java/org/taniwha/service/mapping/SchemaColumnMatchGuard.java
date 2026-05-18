package org.taniwha.service.mapping;

import org.taniwha.model.ColStats;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.util.StringUtil;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies cheap structural checks after the embedding model picks a schema field.
 *
 * Embeddings are useful for recall, but short clinical column names are noisy:
 * numeric status codes can look close to age, and any date can look close to any
 * other date.  This guard requires name/type evidence before a source column is
 * allowed into a schema-backed mapping.
 */
class SchemaColumnMatchGuard {

    private static final Pattern TOKEN = Pattern.compile("[a-z]+|[0-9]+");

    private static final Set<String> WEAK_TOKENS = Set.of(
            "column", "data", "field", "value", "values", "var",
            "date", "time", "dt", "day", "days", "year", "years",
            "status", "state", "type", "kind", "category",
            "code", "coded", "id", "identifier",
            "score", "total", "sum", "index",
            "event", "note", "notes", "comment", "comments",
            "patient", "clinical", "record", "file"
    );

    boolean accepts(EmbeddedSchemaField field, EmbeddedColumn source, double embeddingSimilarity) {
        return matchQuality(field, source, embeddingSimilarity) > 0;
    }

    int matchQuality(EmbeddedSchemaField field, EmbeddedColumn source, double embeddingSimilarity) {
        return matchQuality(field, source, embeddingSimilarity, Collections.emptySet());
    }

    int matchQuality(
            EmbeddedSchemaField field,
            EmbeddedColumn source,
            double embeddingSimilarity,
            Set<String> contextTokens
    ) {
        if (field == null || source == null) return 0;

        NameEvidence names = compareNames(field.name, field.description, source.column, source.concept, contextTokens);
        boolean compatibleType = isTypeCompatible(field, source, names);

        if (!compatibleType) return 0;
        if (names.exactName) return 4;
        if (names.containedName) return 3;
        if (names.strongOverlap) return 2;
        if (hasHighConfidenceTypedEmbedding(field, source, names, embeddingSimilarity)) return 1;

        // Embedding-only schema matches are too loose for integration specs.
        return 0;
    }

    private NameEvidence compareNames(
            String schemaName,
            String schemaDescription,
            String sourceColumn,
            String sourceConcept,
            Set<String> contextTokens
    ) {
        String targetNorm = compact(schemaName);
        String sourceNorm = compact(sourceColumn);
        String conceptNorm = compact(sourceConcept);

        boolean exactName = !targetNorm.isEmpty()
                && (targetNorm.equals(sourceNorm) || targetNorm.equals(conceptNorm));

        boolean containedName = !targetNorm.isEmpty()
                && (containsWholeCompact(sourceNorm, targetNorm)
                || containsWholeCompact(conceptNorm, targetNorm)
                || containsWholeCompact(targetNorm, sourceNorm)
                || containsWholeCompact(targetNorm, conceptNorm));

        Set<String> targetTokens = tokenize(schemaName + " " + StringUtil.safe(schemaDescription));
        Set<String> sourceTokens = tokenize(sourceColumn + " " + sourceConcept);
        Set<String> structuralTargetTokens = withoutContextTokens(targetTokens, contextTokens);
        Set<String> structuralSourceTokens = withoutContextTokens(sourceTokens, contextTokens);

        Set<String> overlap = new LinkedHashSet<>(structuralTargetTokens);
        overlap.retainAll(structuralSourceTokens);

        long strongCount = overlap.stream().filter(t -> !WEAK_TOKENS.contains(t)).count();
        boolean twoTokenPhrase = overlap.size() >= 2;
        boolean acronymOrPrefix = hasAcronymOrPrefixEvidence(structuralTargetTokens, structuralSourceTokens);
        boolean strongOverlap = hasMeaningfulSingleOverlap(overlap, structuralTargetTokens, structuralSourceTokens)
                || twoTokenPhrase
                || acronymOrPrefix;

        return new NameEvidence(exactName, containedName, strongOverlap, targetTokens, sourceTokens);
    }

    private boolean isTypeCompatible(EmbeddedSchemaField field, EmbeddedColumn source, NameEvidence names) {
        String schemaType = normalizeType(field.type);
        ColStats stats = source.stats;
        boolean sourceNumeric = stats != null && (stats.isHasIntegerMarker() || stats.isHasDoubleMarker()
                || stats.getNumMin() != null || stats.getNumMax() != null);
        boolean sourceDate = stats != null && (stats.isHasDateMarker()
                || stats.getDateMinMs() != null || stats.getDateMaxMs() != null);
        boolean sourceNameLooksDate = names.sourceTokens.stream().anyMatch(t ->
                "date".equals(t) || "dt".equals(t) || "time".equals(t));

        if (isDateType(schemaType) || targetLooksDate(names.targetTokens)) {
            if (hasSpecificTemporalTarget(names.targetTokens, names.sourceTokens)) return false;
            return sourceDate || (sourceNameLooksDate && names.strongOverlap);
        }

        if (isNumericType(schemaType)) {
            return sourceNumeric;
        }

        if (schemaType.startsWith("array<")) {
            return names.strongOverlap || names.exactName || names.containedName;
        }

        // String schema fields can legitimately receive coded categorical values
        // (for example sex/gender codes), but only with lexical evidence.
        return true;
    }

    private boolean hasHighConfidenceTypedEmbedding(
            EmbeddedSchemaField field,
            EmbeddedColumn source,
            NameEvidence names,
            double embeddingSimilarity
    ) {
        if (embeddingSimilarity < 0.72) return false;

        String schemaType = normalizeType(field.type);
        ColStats stats = source.stats;
        boolean sourceNumeric = stats != null && (stats.isHasIntegerMarker() || stats.isHasDoubleMarker()
                || stats.getNumMin() != null || stats.getNumMax() != null);
        boolean sourceDate = stats != null && (stats.isHasDateMarker()
                || stats.getDateMinMs() != null || stats.getDateMaxMs() != null);
        boolean sourceDateNamed = names.sourceTokens.stream().anyMatch(t ->
                "date".equals(t) || "dt".equals(t) || "time".equals(t));

        if (isNumericType(schemaType)) {
            if (hasSpecificTemporalTarget(names.targetTokens, names.sourceTokens)) return false;
            if (names.targetTokens.contains("total") && !names.sourceTokens.contains("total")) return false;
            return sourceNumeric && !hasCategoricalCodeShape(names.sourceTokens);
        }

        if (isDateType(schemaType) || targetLooksDate(names.targetTokens)) {
            return sourceDate && sourceDateNamed;
        }

        return !hasCategoricalCodeShape(names.sourceTokens)
                && !names.sourceTokens.contains("event")
                && !names.sourceTokens.contains("note")
                && !names.sourceTokens.contains("notes");
    }

    private boolean hasCategoricalCodeShape(Set<String> tokens) {
        return tokens.contains("code")
                || tokens.contains("coded")
                || tokens.contains("id")
                || tokens.contains("identifier")
                || tokens.contains("status")
                || tokens.contains("state")
                || tokens.contains("type")
                || tokens.contains("category");
    }

    private boolean hasSpecificTemporalTarget(Set<String> targetTokens, Set<String> sourceTokens) {
        Set<String> temporalAnchors = Set.of("birth", "born", "admission", "discharge");
        for (String anchor : temporalAnchors) {
            if (targetTokens.contains(anchor) && !sourceTokens.contains(anchor)) return true;
        }
        return false;
    }

    private boolean targetLooksDate(Set<String> targetTokens) {
        return targetTokens.contains("date") || targetTokens.contains("dt") || targetTokens.contains("time");
    }

    private Set<String> withoutContextTokens(Set<String> tokens, Set<String> contextTokens) {
        if (tokens == null || tokens.isEmpty()) return Collections.emptySet();
        if (contextTokens == null || contextTokens.isEmpty()) return tokens;
        Set<String> out = new LinkedHashSet<>(tokens);
        out.removeAll(contextTokens);
        return out.isEmpty() ? tokens : out;
    }

    private boolean hasMeaningfulSingleOverlap(
            Set<String> overlap,
            Set<String> targetTokens,
            Set<String> sourceTokens
    ) {
        for (String token : overlap) {
            if (WEAK_TOKENS.contains(token)) continue;
            if (token.length() <= 2) continue;
            if (targetTokens.size() == 1 || sourceTokens.size() == 1) return true;
            if (token.length() >= 3) return true;
        }
        return false;
    }

    private boolean isNumericType(String schemaType) {
        return "integer".equals(schemaType)
                || "number".equals(schemaType)
                || "double".equals(schemaType)
                || "float".equals(schemaType)
                || "long".equals(schemaType);
    }

    private boolean isDateType(String schemaType) {
        return "date".equals(schemaType)
                || "datetime".equals(schemaType)
                || "timestamp".equals(schemaType);
    }

    private String normalizeType(String raw) {
        return StringUtil.safeTrim(raw).toLowerCase(Locale.ROOT);
    }

    private Set<String> tokenize(String text) {
        String prepared = StringUtil.safe(text)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ");

        Set<String> out = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(prepared);
        while (m.find()) {
            String token = m.group();
            if (token.isBlank()) continue;
            out.add(stem(token));
            if (token.endsWith("use") && token.length() > 4) {
                out.add(stem(token.substring(0, token.length() - 3)));
                out.add("use");
            }
        }
        return out;
    }

    private String stem(String token) {
        String t = StringUtil.safeTrim(token).toLowerCase(Locale.ROOT);
        if (t.length() > 4 && t.endsWith("ies")) return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 5 && t.endsWith("ing")) return t.substring(0, t.length() - 3);
        if (t.length() > 4 && t.endsWith("ed")) return t.substring(0, t.length() - 2);
        if (t.length() > 3 && t.endsWith("s")) return t.substring(0, t.length() - 1);
        return t;
    }

    private String compact(String text) {
        return StringUtil.safe(text)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "");
    }

    private boolean containsWholeCompact(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        if (needle.length() < 4) return false;
        return haystack.contains(needle);
    }

    private boolean hasAcronymOrPrefixEvidence(Set<String> targetTokens, Set<String> sourceTokens) {
        for (String target : targetTokens) {
            if (WEAK_TOKENS.contains(target) || target.length() < 3) continue;
            for (String source : sourceTokens) {
                if (WEAK_TOKENS.contains(source) || source.length() < 3) continue;
                if (target.startsWith(source) || source.startsWith(target)) return true;
                if (isAcronym(source, targetTokens) || isAcronym(target, sourceTokens)) return true;
            }
        }
        return false;
    }

    private boolean isAcronym(String maybeAcronym, Set<String> tokens) {
        if (maybeAcronym == null || maybeAcronym.length() < 2 || maybeAcronym.length() > 6) return false;
        List<String> strong = tokens.stream()
                .filter(t -> !WEAK_TOKENS.contains(t))
                .filter(t -> t.length() >= 2)
                .toList();
        if (strong.size() < 2) return false;

        StringBuilder initials = new StringBuilder();
        for (String token : strong) initials.append(token.charAt(0));
        return initials.toString().startsWith(maybeAcronym);
    }

    private record NameEvidence(
            boolean exactName,
            boolean containedName,
            boolean strongOverlap,
            Set<String> targetTokens,
            Set<String> sourceTokens
    ) {}

}
