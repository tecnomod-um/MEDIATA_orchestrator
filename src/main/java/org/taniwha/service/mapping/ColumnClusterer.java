package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.model.ColCluster;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.util.MappingMathUtil;
import org.taniwha.util.StringUtil;

import java.util.*;

// Groups semantically similar source columns into shared mapping concepts.
class ColumnClusterer {

    private static final Logger logger = LoggerFactory.getLogger(ColumnClusterer.class);

    private static final Set<String> GRAMMATICAL_SUFFIXES = Set.of("ing", "ed", "er", "ers", "s", "es", "age");

    // High-frequency temporal/count words inflate overlap without helping semantic grouping.
    private static final Set<String> JACCARD_STOP_TOKENS =
            Set.of("date", "dt", "time", "day", "days", "week", "weeks", "month", "months",
                    "year", "years", "hour", "hours", "minute", "minutes", "total",
                    "status", "state", "type", "code", "id", "value", "score", "scores",
                    "scale", "scales", "level", "levels", "management", "control", "controls");

    private static final Set<String> BOOLEAN_LIKE_VALUES = Set.of(
            "yes", "no", "true", "false", "y", "n", "0", "1",
            "positive", "negative", "present", "absent"
    );

    // Value evidence is weaker than name similarity, so the merge threshold stays deliberately conservative.
    private static final double VALUE_EMBEDDING_THRESHOLD = 0.30;

    private static final double VALUE_EMBEDDING_IDENTICAL = 0.99;

    private static final double MUTUAL_SEMANTIC_SCORE_THRESHOLD = 0.82;

    private static final Set<String> SEMANTIC_FALLBACK_EXCLUDED_TOKENS = Set.of(
            "id", "identifier", "code", "status", "state", "type", "category",
            "date", "dt", "time", "day", "days", "year", "years", "age",
            "sex", "gender", "diagnosis", "outcome", "event", "note", "notes",
            "total", "tot", "sum", "index", "score", "scores"
    );

    private static final Set<String> VALUE_EVIDENCE_EXCLUDED_TOKENS = Set.of(
            "id", "identifier", "date", "dt", "time", "day", "days", "year", "years", "age",
            "total", "tot", "sum", "index",
            "outcome", "event", "note", "notes", "diagnosis", "admission", "discharge"
    );

    private enum ColumnRole {
        IDENTIFIER,
        DATE,
        DURATION,
        AGE,
        LIFE_EVENT_YEAR,
        TOTAL_SCORE,
        ITEM_SCORE,
        NUMERIC_MEASURE,
        BOOLEAN_FLAG,
        OUTCOME,
        DIAGNOSIS,
        FREE_TEXT,
        CATEGORICAL,
        UNKNOWN
    }

    private final MappingServiceSettings settings;

    ColumnClusterer(MappingServiceSettings settings) {
        this.settings = settings;
    }

    List<ColCluster> clusterColumns(List<EmbeddedColumn> all) {
        return clusterColumns(all, Collections.emptySet());
    }

    List<ColCluster> clusterColumns(List<EmbeddedColumn> all, Set<String> requestContextTokens) {
        Set<String> tokenOverlapExclusions = normalizedTokenSet(requestContextTokens);
        Map<String, ColCluster> byConcept = new LinkedHashMap<>();
        for (EmbeddedColumn c : all) {
            String key = sanitizeUnionName(StringUtil.safeTrim(c.concept).toLowerCase(Locale.ROOT));
            if (key.isEmpty()) key = "__empty__";
            key = key + "__role_" + initialRoleKey(inferRole(c));
            byConcept.computeIfAbsent(key, k -> new ColCluster()).add(c);
        }

        List<ColCluster> clusters = new ArrayList<>(byConcept.values());
        List<ColCluster> merged = new ArrayList<>();

        for (ColCluster col : clusters) {
            // Every cluster keeps one stable human-readable concept for later mapping and enrichment stages.
            col.representativeConcept = pickClusterRepresentativeConcept(col.cols);
            String colConcept = col.representativeConcept;

            ColCluster bestMatch = null;
            double bestMatchSim = -1;

            for (ColCluster cl : merged) {
                if (!rolesCompatible(col, cl)) continue;

                double sim = MappingMathUtil.cosine(col.centroid, cl.centroid);

                double jac = conceptTokenJaccard(col, cl, tokenOverlapExclusions);
                boolean hasTokenOverlap = jac >= 0.15;

                boolean hasAbbrevPair = false;
                if (!hasTokenOverlap) {
                    for (EmbeddedColumn clCol : cl.cols) {
                        if (isAbbreviationPair(colConcept, clCol.concept)) {
                            hasAbbrevPair = true;
                            break;
                        }
                    }
                }

                if (hasExclusiveLifeEventAnchor(col, cl) && !hasAbbrevPair) continue;

                boolean hasValueEvidence = false;
                if (!hasTokenOverlap && !hasAbbrevPair) {
                    // Value-domain similarity only acts as a fallback when names provide no usable structure.
                    hasValueEvidence = hasValueEmbeddingSimilarity(col, cl)
                            && !isScoreLikeCluster(col)
                            && !isScoreLikeCluster(cl)
                            && !isBooleanLikeCluster(col)
                            && !isBooleanLikeCluster(cl)
                            && !containsExcludedValueToken(col)
                            && !containsExcludedValueToken(cl);
                }

                boolean hasMutualSemanticEvidence = false;

                if (!(hasTokenOverlap || hasAbbrevPair || hasValueEvidence || hasMutualSemanticEvidence)) continue;

                logger.debug("[CLUSTER] '{}' vs '{}': sim={}, jac={}, abbrev={}, valueEvidence={}, mutualSemantic={}",
                        colConcept, cl.representativeConcept, sim, jac, hasAbbrevPair, hasValueEvidence,
                        hasMutualSemanticEvidence);

                double ensembleScore = ensembleScore(col, cl, sim, jac, hasAbbrevPair, hasValueEvidence,
                        hasMutualSemanticEvidence);
                if (ensembleScore >= settings.columnClusterThreshold() && ensembleScore > bestMatchSim) {
                    bestMatchSim = ensembleScore;
                    bestMatch = cl;
                }
            }

            if (bestMatch != null) {
                for (EmbeddedColumn r : col.cols) bestMatch.add(r);
                bestMatch.representativeConcept = pickClusterRepresentativeConcept(bestMatch.cols);
            } else {
                merged.add(col);
            }
        }

        for (ColCluster c : merged) {
            if (c.representativeConcept == null || c.representativeConcept.isEmpty()) {
                c.representativeConcept = pickClusterRepresentativeConcept(c.cols);
            }
        }

        return merged;
    }

    private String initialRoleKey(ColumnRole role) {
        if (role == null || role == ColumnRole.UNKNOWN) return "unknown";
        return role.name().toLowerCase(Locale.ROOT);
    }

    private double ensembleScore(
            ColCluster a,
            ColCluster b,
            double semanticSimilarity,
            double tokenJaccard,
            boolean hasAbbrevPair,
            boolean hasValueEvidence,
            boolean hasMutualSemanticEvidence
    ) {
        // COMA-style lightweight ensemble: embeddings stay primary, while lexical,
        // value-profile and inferred-role evidence nudge borderline decisions.
        double lexicalEvidence = Math.max(tokenJaccard, hasAbbrevPair ? 0.45 : 0.0);
        double valueEvidence = hasValueEvidence ? 0.25 : 0.0;
        double mutualEvidence = hasMutualSemanticEvidence ? 0.20 : 0.0;
        double roleEvidence = roleEvidence(a, b);

        double score = semanticSimilarity
                + 0.16 * lexicalEvidence
                + 0.08 * valueEvidence
                + 0.06 * mutualEvidence
                + 0.08 * roleEvidence;
        return Math.min(1.0, score);
    }

    private double roleEvidence(ColCluster a, ColCluster b) {
        ColumnRole ra = inferRole(a);
        ColumnRole rb = inferRole(b);
        if (ra == ColumnRole.UNKNOWN || rb == ColumnRole.UNKNOWN) return 0.0;
        if (ra == rb) return 1.0;
        if (isNumericAssessmentRole(ra) && isNumericAssessmentRole(rb)) return 0.45;
        if (isTemporalRole(ra) && isTemporalRole(rb)) return 0.35;
        return 0.0;
    }

    private boolean rolesCompatible(ColCluster a, ColCluster b) {
        if (hasUncoveredCompositeLabel(a, b) || hasUncoveredCompositeLabel(b, a)) return false;

        ColumnRole ra = inferRole(a);
        ColumnRole rb = inferRole(b);

        if (ra == ColumnRole.UNKNOWN || rb == ColumnRole.UNKNOWN) return true;
        if (ra == rb) return true;

        if (ra == ColumnRole.IDENTIFIER || rb == ColumnRole.IDENTIFIER) return false;
        if (ra == ColumnRole.FREE_TEXT || rb == ColumnRole.FREE_TEXT) return false;
        if (ra == ColumnRole.DIAGNOSIS || rb == ColumnRole.DIAGNOSIS) return false;
        if (ra == ColumnRole.OUTCOME || rb == ColumnRole.OUTCOME) return false;
        if (ra == ColumnRole.BOOLEAN_FLAG || rb == ColumnRole.BOOLEAN_FLAG) return false;
        if (isTemporalRole(ra) || isTemporalRole(rb)) return ra == rb;
        if (ra == ColumnRole.AGE || rb == ColumnRole.AGE) return false;
        if (ra == ColumnRole.LIFE_EVENT_YEAR || rb == ColumnRole.LIFE_EVENT_YEAR) return false;

        // A scale total/subscale score and an individual item score can be close in
        // embedding space, but they answer different harmonization questions.
        if ((ra == ColumnRole.TOTAL_SCORE && rb == ColumnRole.ITEM_SCORE)
                || (ra == ColumnRole.ITEM_SCORE && rb == ColumnRole.TOTAL_SCORE)) {
            return false;
        }

        if (isNumericAssessmentRole(ra) && isNumericAssessmentRole(rb)) return true;
        return false;
    }

    private boolean isNumericAssessmentRole(ColumnRole role) {
        return role == ColumnRole.TOTAL_SCORE
                || role == ColumnRole.ITEM_SCORE
                || role == ColumnRole.NUMERIC_MEASURE;
    }

    private boolean isTemporalRole(ColumnRole role) {
        return role == ColumnRole.DATE || role == ColumnRole.DURATION;
    }

    private ColumnRole inferRole(ColCluster cluster) {
        if (cluster == null || cluster.cols == null || cluster.cols.isEmpty()) return ColumnRole.UNKNOWN;

        EnumMap<ColumnRole, Integer> votes = new EnumMap<>(ColumnRole.class);
        for (EmbeddedColumn col : cluster.cols) {
            ColumnRole role = inferRole(col);
            votes.merge(role, 1, Integer::sum);
        }

        List<ColumnRole> priority = List.of(
                ColumnRole.IDENTIFIER,
                ColumnRole.DATE,
                ColumnRole.DURATION,
                ColumnRole.LIFE_EVENT_YEAR,
                ColumnRole.AGE,
                ColumnRole.TOTAL_SCORE,
                ColumnRole.ITEM_SCORE,
                ColumnRole.BOOLEAN_FLAG,
                ColumnRole.OUTCOME,
                ColumnRole.DIAGNOSIS,
                ColumnRole.FREE_TEXT,
                ColumnRole.NUMERIC_MEASURE,
                ColumnRole.CATEGORICAL,
                ColumnRole.UNKNOWN
        );

        ColumnRole best = ColumnRole.UNKNOWN;
        int bestVotes = -1;
        for (ColumnRole role : priority) {
            int voteCount = votes.getOrDefault(role, 0);
            if (voteCount > bestVotes) {
                best = role;
                bestVotes = voteCount;
            }
        }
        return best;
    }

    private ColumnRole inferRole(EmbeddedColumn col) {
        String label = StringUtil.safe(col == null ? "" : col.column);
        Set<String> tokens = roleTokens(label);
        Set<String> conceptTokens = roleTokens(col == null ? "" : col.concept);
        tokens.addAll(conceptTokens);

        boolean numeric = isNumericColumn(col);
        boolean date = col != null && col.stats != null
                && (col.stats.isHasDateMarker()
                || col.stats.getDateMinMs() != null
                || col.stats.getDateMaxMs() != null);

        if (containsAny(tokens, "id", "identifier", "subject", "patientid", "recordid")) return ColumnRole.IDENTIFIER;
        if (date || containsAny(tokens, "date", "dt", "timestamp")) return ColumnRole.DATE;
        if (containsAny(tokens, "duration", "los", "tsi", "elapsed")
                || (numeric && containsAny(tokens, "day", "days", "week", "weeks", "month", "months"))) {
            return ColumnRole.DURATION;
        }
        if (containsAny(tokens, "birth", "born", "death", "deceased")) return ColumnRole.LIFE_EVENT_YEAR;
        if (containsAny(tokens, "age", "ageinjury")) return ColumnRole.AGE;
        if (containsAny(tokens, "diagnosis", "diagnostic", "dx", "icd", "snomed")) return ColumnRole.DIAGNOSIS;
        if (isBooleanLikeColumn(col) || containsAny(tokens, "flag", "indicator", "yesno")) return ColumnRole.BOOLEAN_FLAG;
        if (containsAny(tokens, "note", "notes", "comment", "comments", "text", "description")) return ColumnRole.FREE_TEXT;
        if (containsAny(tokens, "outcome", "event", "status", "state")) return ColumnRole.OUTCOME;

        if (numeric && (containsAny(tokens, "total", "tot", "sum", "index", "subscale")
                || containsTokenPrefix(tokens, "total", "tot")
                || containsTokenSuffix(tokens, "total", "index"))) {
            return ColumnRole.TOTAL_SCORE;
        }
        if (numeric && looksLikeAssessmentItem(tokens)) return ColumnRole.ITEM_SCORE;
        if (numeric) return ColumnRole.NUMERIC_MEASURE;
        if (hasCategoricalValues(col)) return ColumnRole.CATEGORICAL;
        return ColumnRole.UNKNOWN;
    }

    private boolean looksLikeAssessmentItem(Set<String> tokens) {
        return containsAny(tokens,
                "eating", "eat", "feeding", "feed", "bath", "bathing", "groom", "grooming",
                "toilet", "toileting", "transfer", "transfers", "mobility", "locomotion",
                "stairs", "dressing", "dress", "bowel", "bladder", "memory", "comprehension",
                "expression", "interaction", "problem", "solving", "walking", "ambulation")
                || containsTokenSuffix(tokens,
                "eating", "feeding", "bath", "bathing", "grooming", "toilet", "toileting",
                "transfer", "transfers", "mobility", "stairs", "dressing", "bowel", "bladder");
    }

    private boolean isNumericColumn(EmbeddedColumn col) {
        return col != null && col.stats != null
                && (col.stats.isHasIntegerMarker()
                || col.stats.isHasDoubleMarker()
                || col.stats.getNumMin() != null
                || col.stats.getNumMax() != null);
    }

    private boolean hasCategoricalValues(EmbeddedColumn col) {
        if (col == null || col.rawValues == null) return false;
        for (String raw : col.rawValues) {
            String value = StringUtil.safeTrim(raw).toLowerCase(Locale.ROOT);
            if (value.isEmpty()
                    || "integer".equals(value)
                    || "double".equals(value)
                    || "date".equals(value)
                    || value.startsWith("min:")
                    || value.startsWith("max:")
                    || value.startsWith("earliest:")
                    || value.startsWith("latest:")
                    || value.startsWith("step:")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isBooleanLikeColumn(EmbeddedColumn col) {
        if (col == null || col.rawValues == null) return false;
        Set<String> values = new LinkedHashSet<>();
        for (String raw : col.rawValues) {
            String value = StringUtil.safeTrim(raw).toLowerCase(Locale.ROOT);
            if (value.isEmpty()
                    || "integer".equals(value)
                    || "double".equals(value)
                    || "date".equals(value)
                    || value.startsWith("min:")
                    || value.startsWith("max:")
                    || value.startsWith("earliest:")
                    || value.startsWith("latest:")
                    || value.startsWith("step:")) {
                continue;
            }
            values.add(value);
            if (values.size() > 3) return false;
        }
        if (values.isEmpty()) return false;
        long booleanLike = values.stream().filter(BOOLEAN_LIKE_VALUES::contains).count();
        return booleanLike >= 2 || (values.size() <= 2 && booleanLike >= 1);
    }

    private boolean containsAny(Set<String> tokens, String... candidates) {
        for (String candidate : candidates) {
            if (tokens.contains(candidate)) return true;
        }
        return false;
    }

    private boolean containsTokenPrefix(Set<String> tokens, String... prefixes) {
        for (String token : tokens) {
            for (String prefix : prefixes) {
                if (token.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    private boolean containsTokenSuffix(Set<String> tokens, String... suffixes) {
        for (String token : tokens) {
            for (String suffix : suffixes) {
                if (token.endsWith(suffix)) return true;
            }
        }
        return false;
    }

    private Set<String> roleTokens(String label) {
        String prepared = StringUtil.safe(label)
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .toLowerCase(Locale.ROOT);
        String[] parts = prepared.split("[^a-z0-9]+");
        Set<String> out = new LinkedHashSet<>();
        for (String part : parts) {
            if (part == null) continue;
            String token = part.trim();
            if (token.isEmpty() || token.matches("\\d+") || token.length() <= 1) continue;
            out.add(token);
        }
        return out;
    }

    private static final Set<String> STRUCTURAL_CONCEPT_WORDS =
            Set.of("id", "code", "name", "value", "flag", "indicator", "status", "type");

    private boolean isStructuralSingleTokenConcept(String concept) {
        Set<String> tokens = rawConceptTokens(concept);
        return tokens.size() == 1 && STRUCTURAL_CONCEPT_WORDS.contains(tokens.iterator().next());
    }

    String pickClusterRepresentativeConcept(List<EmbeddedColumn> cols) {
        // Prefer the shortest non-structural label so abbreviations do not dominate the published concept.
        String best = null;
        int bestLen = Integer.MAX_VALUE;

        for (EmbeddedColumn c : cols) {
            String name = StringUtil.safeTrim(c.concept);
            if (name.isEmpty() || isStructuralSingleTokenConcept(name)) continue;
            int len = name.length();
            if (len < bestLen) {
                best = name;
                bestLen = len;
            } else if (len == bestLen && best != null && name.compareToIgnoreCase(best) < 0) {
                best = name;
            }
        }

        if (best == null) {
            bestLen = Integer.MAX_VALUE;
            for (EmbeddedColumn c : cols) {
                String name = StringUtil.safeTrim(c.concept);
                if (name.isEmpty()) continue;
                int len = name.length();
                if (len < bestLen) {
                    best = name;
                    bestLen = len;
                } else if (len == bestLen && best != null && name.compareToIgnoreCase(best) < 0) {
                    best = name;
                }
            }
        }

        if (best == null) best = cols.get(0).concept;
        return best;
    }

    private double conceptTokenJaccard(ColCluster a, ColCluster b, Set<String> tokenOverlapExclusions) {
        Set<String> sa = clusterConceptTokens(a, tokenOverlapExclusions);
        Set<String> sb = clusterConceptTokens(b, tokenOverlapExclusions);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;

        int inter = 0;
        for (String x : sa) if (sb.contains(x)) inter++;
        int union = sa.size() + sb.size() - inter;
        if (union <= 0) return 0.0;
        return inter / (double) union;
    }

    private Set<String> clusterConceptTokens(ColCluster cluster, Set<String> tokenOverlapExclusions) {
        Set<String> out = new LinkedHashSet<>();
        if (cluster == null) return out;
        out.addAll(conceptTokens(cluster.representativeConcept));
        if (cluster.cols != null) {
            for (EmbeddedColumn col : cluster.cols) {
                out.addAll(conceptTokens(col == null ? "" : col.column, tokenOverlapExclusions));
                out.addAll(conceptTokens(col == null ? "" : col.concept));
            }
        }
        return out;
    }

    private Set<String> conceptTokens(String concept) {
        return conceptTokens(concept, Collections.emptySet());
    }

    private Set<String> conceptTokens(String concept, Set<String> tokenOverlapExclusions) {
        String s = StringUtil.safeTrim(concept).toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return Collections.emptySet();
        String[] parts = s.split("[^a-z0-9]+");
        Set<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty() || t.matches("\\d+") || t.length() <= 1
                    || JACCARD_STOP_TOKENS.contains(t)
                    || tokenOverlapExclusions.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    private Set<String> normalizedTokenSet(Set<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String token : tokens) {
            String t = StringUtil.safeTrim(token).toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private boolean isAbbreviationPair(String conceptA, String conceptB) {
        if (conceptA == null || conceptB == null) return false;
        return looksLikeAbbreviationOf(conceptA, conceptB)
                || looksLikeAbbreviationOf(conceptB, conceptA);
    }

    private boolean looksLikeAbbreviationOf(String candidate, String fullConcept) {
        String[] candidateTokens = candidate.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        String[] fullTokensArr = fullConcept.trim().toLowerCase(Locale.ROOT).split("[^a-z0-9]+");

        if (hasCompositeJoiner(fullConcept)
                && !hasCompositeJoiner(candidate)
                && !candidateCoversCompositeHeads(candidateTokens, fullConcept)) {
            return false;
        }

        List<String> fullParts = new ArrayList<>();
        for (String t : fullTokensArr) { if (t != null && !t.isEmpty()) fullParts.add(t); }

        if (fullParts.size() == 1) {
            String singleToken = fullParts.get(0);
            if (singleToken.length() < 4) return false;
            for (String abbrev : candidateTokens) {
                if (abbrev == null || abbrev.length() < 3) continue;
                // Single-word concepts often surface as clipped stems like "mobil" for "mobility".
                if (singleToken.length() > abbrev.length() && singleToken.startsWith(abbrev)) {
                    String remainder = singleToken.substring(abbrev.length());
                    if (GRAMMATICAL_SUFFIXES.contains(remainder)) return true;
                }
                if (abbrev.endsWith("e") && abbrev.length() > 3) {
                    // Allow the common silent-e drop before checking grammatical suffix leftovers.
                    String stem = abbrev.substring(0, abbrev.length() - 1);
                    if (singleToken.length() > stem.length() && singleToken.startsWith(stem)) {
                        String remainder = singleToken.substring(stem.length());
                        if (GRAMMATICAL_SUFFIXES.contains(remainder)) return true;
                    }
                }
            }
            return false;
        }
        if (fullParts.size() < 2) return false;

        String initials = initialsOf(fullParts);

        for (String abbrev : candidateTokens) {
            if (abbrev == null || abbrev.isEmpty()) continue;
            // Two-character multi-token abbreviations collide too often unless they match the full token count.
            if (abbrev.length() < 2 || abbrev.length() > 6) continue;

            if (candidateTokens.length > 1 && fullParts.size() > 1
                    && abbrev.length() < 3 && abbrev.length() != fullParts.size()) continue;

            if (orderedInitialsMatch(abbrev, initials)) return true;

            for (String token : fullParts) {
                if (token.length() > abbrev.length() && token.startsWith(abbrev)) {
                    if (candidateTokens.length > 1 && fullParts.size() > 1) {
                        // Acronym-like tokens can stand in for a remaining stem when other parts already align.
                        if (isAcronymLike(token)) return true;
                        if (remainingTokensMatch(abbrev, candidateTokens, token, fullParts)) return true;
                    } else {
                        return true;
                    }
                }
            }

            for (int start = 1; start <= abbrev.length() - 2; start++) {
                // Suffix initialisms cover exports that drop leading context before keeping initials.
                String suffix = abbrev.substring(start);
                if (suffix.length() < 2) continue;
                if (orderedInitialsMatch(suffix, initials)) return true;
            }
        }
        return false;
    }

    private static String initialsOf(List<String> parts) {
        StringBuilder initials = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) initials.append(part.charAt(0));
        }
        return initials.toString();
    }

    private static boolean orderedInitialsMatch(String abbreviation, String initials) {
        if (abbreviation == null || initials == null || abbreviation.length() < 2 || initials.length() < 2) {
            return false;
        }
        return initials.startsWith(abbreviation)
                || abbreviation.startsWith(initials)
                || (initials.length() > 2 && initials.substring(1).equals(abbreviation));
    }

    private boolean hasUncoveredCompositeLabel(ColCluster composite, ColCluster other) {
        List<String> heads = compositeHeads(composite);
        if (heads.size() < 2) return false;
        String otherText = clusterSearchText(other);
        if (otherText.isEmpty()) return false;
        for (String head : heads) {
            if (!otherText.contains(head)) return true;
        }
        return false;
    }

    private List<String> compositeHeads(ColCluster cluster) {
        if (cluster == null) return Collections.emptyList();
        List<String> labels = new ArrayList<>();
        labels.add(StringUtil.safe(cluster.representativeConcept));
        if (cluster.cols != null) {
            for (EmbeddedColumn col : cluster.cols) {
                labels.add(StringUtil.safe(col == null ? "" : col.column));
                labels.add(StringUtil.safe(col == null ? "" : col.concept));
            }
        }

        for (String label : labels) {
            if (!hasCompositeJoiner(label)) continue;
            List<String> heads = new ArrayList<>();
            for (String segment : label.toLowerCase(Locale.ROOT).split("\\+")) {
                String[] tokens = segment.split("[^a-z0-9]+");
                for (String token : tokens) {
                    if (token != null && token.length() >= 2) {
                        heads.add(token);
                        break;
                    }
                }
            }
            if (heads.size() >= 2) return heads;
        }
        return Collections.emptyList();
    }

    private String clusterSearchText(ColCluster cluster) {
        if (cluster == null) return "";
        StringBuilder sb = new StringBuilder();
        appendSearchText(sb, cluster.representativeConcept);
        if (cluster.cols != null) {
            for (EmbeddedColumn col : cluster.cols) {
                appendSearchText(sb, col == null ? "" : col.column);
                appendSearchText(sb, col == null ? "" : col.concept);
            }
        }
        return sb.toString();
    }

    private void appendSearchText(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(' ')
                .append(value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", ""));
    }

    private static boolean hasCompositeJoiner(String value) {
        return value != null && value.contains("+");
    }

    private static boolean candidateCoversCompositeHeads(String[] candidateTokens, String fullConcept) {
        if (candidateTokens == null || fullConcept == null) return false;
        String candidate = String.join("", candidateTokens).toLowerCase(Locale.ROOT);
        if (candidate.isEmpty()) return false;

        List<String> heads = new ArrayList<>();
        for (String segment : fullConcept.toLowerCase(Locale.ROOT).split("\\+")) {
            String[] tokens = segment.split("[^a-z0-9]+");
            for (String token : tokens) {
                if (token != null && token.length() >= 2) {
                    heads.add(token);
                    break;
                }
            }
        }
        if (heads.size() < 2) return true;
        for (String head : heads) {
            if (!candidate.contains(head)) return false;
        }
        return true;
    }

    private static boolean remainingTokensMatch(
            String matchedCandidateToken, String[] candidateTokens,
            String matchedFullToken,      List<String> fullParts) {
        // Once one token is explained as a stem, the rest still need to align to avoid accidental merges.
        List<String> remaining = new ArrayList<>(fullParts.size());
        boolean excluded = false;
        for (String ft : fullParts) {
            if (!excluded && ft.equals(matchedFullToken)) { excluded = true; continue; }
            remaining.add(ft);
        }
        for (String ct : candidateTokens) {
            if (ct == null || ct.equals(matchedCandidateToken)) continue;
            boolean found = false;
            for (String ft : remaining) {
                if (ft.equals(ct) || ft.startsWith(ct) || ct.startsWith(ft)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean isAcronymLike(String token) {
        if (token == null || token.length() < 3 || token.length() > 6) return false;
        long vowels = 0;
        for (char c : token.toCharArray()) {
            if ("aeiou".indexOf(c) >= 0) vowels++;
        }
        return vowels <= 1;
    }

    private boolean hasValueEmbeddingSimilarity(ColCluster a, ColCluster b) {
        if (a.valueCentroid == null || b.valueCentroid == null) return false;
        // Value vectors only help when both sides have enough non-empty domain evidence to compare.
        double sim = MappingMathUtil.cosine(a.valueCentroid, b.valueCentroid);
        logger.debug("[CLUSTER] value-ngram sim '{}' vs '{}': {}", a.representativeConcept, b.representativeConcept, sim);
        if (sim >= VALUE_EMBEDDING_IDENTICAL) return false;
        return sim >= VALUE_EMBEDDING_THRESHOLD;
    }

    private boolean hasMutualSemanticScoreEvidence(
            ColCluster a,
            ColCluster b,
            List<ColCluster> candidates,
            double similarity
    ) {
        double threshold = Math.max(settings.columnClusterThreshold(), MUTUAL_SEMANTIC_SCORE_THRESHOLD);
        if (similarity < threshold) return false;
        if (!isScoreLikeCluster(a) || !isScoreLikeCluster(b)) return false;
        if (containsExcludedSemanticToken(a) || containsExcludedSemanticToken(b)) return false;

        ColCluster nearestToA = nearestSemanticScoreCluster(a, candidates);
        ColCluster nearestToB = nearestSemanticScoreCluster(b, candidates);
        return nearestToA == b && nearestToB == a;
    }

    private ColCluster nearestSemanticScoreCluster(ColCluster target, List<ColCluster> candidates) {
        ColCluster best = null;
        double bestSim = -1.0;
        for (ColCluster candidate : candidates) {
            if (candidate == target) continue;
            if (!isScoreLikeCluster(candidate) || containsExcludedSemanticToken(candidate)) continue;
            double sim = MappingMathUtil.cosine(target.centroid, candidate.centroid);
            if (sim > bestSim) {
                bestSim = sim;
                best = candidate;
            }
        }
        return bestSim >= Math.max(settings.columnClusterThreshold(), MUTUAL_SEMANTIC_SCORE_THRESHOLD) ? best : null;
    }

    private boolean isScoreLikeCluster(ColCluster cluster) {
        if (cluster == null || cluster.cols == null || cluster.cols.isEmpty()) return false;
        for (EmbeddedColumn col : cluster.cols) {
            if (col.stats == null) return false;
            boolean numeric = col.stats.isHasIntegerMarker()
                    || col.stats.isHasDoubleMarker()
                    || col.stats.getNumMin() != null
                    || col.stats.getNumMax() != null;
            boolean date = col.stats.isHasDateMarker()
                    || col.stats.getDateMinMs() != null
                    || col.stats.getDateMaxMs() != null;
            if (!numeric || date) return false;
        }
        return true;
    }

    private boolean containsExcludedSemanticToken(ColCluster cluster) {
        Set<String> tokens = rawConceptTokens(cluster.representativeConcept);
        for (String token : tokens) {
            if (SEMANTIC_FALLBACK_EXCLUDED_TOKENS.contains(token)) return true;
        }
        return tokens.isEmpty();
    }

    private boolean containsExcludedValueToken(ColCluster cluster) {
        Set<String> tokens = rawConceptTokens(cluster.representativeConcept);
        for (String token : tokens) {
            if (VALUE_EVIDENCE_EXCLUDED_TOKENS.contains(token)) return true;
        }
        return tokens.isEmpty();
    }

    private boolean isBooleanLikeCluster(ColCluster cluster) {
        if (cluster == null || cluster.cols == null || cluster.cols.isEmpty()) return false;
        Set<String> values = new LinkedHashSet<>();
        for (EmbeddedColumn col : cluster.cols) {
            if (col.rawValues == null) continue;
            for (String raw : col.rawValues) {
                String value = StringUtil.safeTrim(raw).toLowerCase(Locale.ROOT);
                if (value.isEmpty()
                        || "integer".equals(value)
                        || "double".equals(value)
                        || "date".equals(value)
                        || value.startsWith("min:")
                        || value.startsWith("max:")
                        || value.startsWith("earliest:")
                        || value.startsWith("latest:")
                        || value.startsWith("step:")) {
                    continue;
                }
                values.add(value);
                if (values.size() > 2) return false;
            }
        }
        if (values.isEmpty()) return false;
        long booleanLike = values.stream().filter(BOOLEAN_LIKE_VALUES::contains).count();
        return booleanLike >= 2 || (values.size() <= 2 && booleanLike >= 1);
    }

    private Set<String> rawConceptTokens(String concept) {
        String s = org.taniwha.util.NormalizationUtil.splitCamelStrong(StringUtil.safeTrim(concept));
        s = s.replaceAll("([a-zA-Z])([0-9])", "$1 $2");
        s = s.replaceAll("([0-9])([a-zA-Z])", "$1 $2");
        s = s.toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return Collections.emptySet();
        String[] parts = s.split("[^a-z0-9]+");
        Set<String> out = new LinkedHashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty() || t.matches("\\d+") || t.length() <= 1) continue;
            out.add(t);
        }
        return out;
    }

    private boolean hasExclusiveLifeEventAnchor(ColCluster a, ColCluster b) {
        return hasLifeEventAnchor(a) != hasLifeEventAnchor(b);
    }

    private boolean hasLifeEventAnchor(ColCluster cluster) {
        if (cluster == null || cluster.cols == null) return false;
        for (EmbeddedColumn col : cluster.cols) {
            Set<String> tokens = rawConceptTokens(col == null ? "" : col.concept);
            if (tokens.contains("birth") || tokens.contains("born")
                    || tokens.contains("death") || tokens.contains("deceased")) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeUnionName(String raw) {
        String s = StringUtil.safeTrim(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }
}
