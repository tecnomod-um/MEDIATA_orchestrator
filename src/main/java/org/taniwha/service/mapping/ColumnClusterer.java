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
            Set.of("total", "days", "weeks", "months", "years", "hours", "minutes");

    // Value evidence is weaker than name similarity, so the merge threshold stays deliberately conservative.
    private static final double VALUE_EMBEDDING_THRESHOLD = 0.30;

    private static final double VALUE_EMBEDDING_IDENTICAL = 0.99;

    private final MappingServiceSettings settings;

    ColumnClusterer(MappingServiceSettings settings) {
        this.settings = settings;
    }

    List<ColCluster> clusterColumns(List<EmbeddedColumn> all) {
        Map<String, ColCluster> byConcept = new LinkedHashMap<>();
        for (EmbeddedColumn c : all) {
            String key = sanitizeUnionName(StringUtil.safeTrim(c.concept).toLowerCase(Locale.ROOT));
            if (key.isEmpty()) key = "__empty__";
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
                double sim = MappingMathUtil.cosine(col.centroid, cl.centroid);

                double jac = conceptTokenJaccard(col, cl);
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

                boolean hasValueEvidence = false;
                if (!hasTokenOverlap && !hasAbbrevPair) {
                    // Value-domain similarity only acts as a fallback when names provide no usable structure.
                    hasValueEvidence = hasValueEmbeddingSimilarity(col, cl);
                }

                if (!(hasTokenOverlap || hasAbbrevPair || hasValueEvidence)) continue;

                logger.debug("[CLUSTER] '{}' vs '{}': sim={}, jac={}, abbrev={}, valueEvidence={}",
                        colConcept, cl.representativeConcept, sim, jac, hasAbbrevPair, hasValueEvidence);

                if (sim >= settings.columnClusterThreshold() && sim > bestMatchSim) {
                    bestMatchSim = sim;
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

    private static final Set<String> STRUCTURAL_CONCEPT_WORDS =
            Set.of("id", "code", "name", "value", "flag", "indicator", "status", "type");

    private boolean isStructuralSingleTokenConcept(String concept) {
        Set<String> tokens = conceptTokens(concept);
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
            if (t.isEmpty() || t.matches("\\d+") || t.length() <= 1
                    || JACCARD_STOP_TOKENS.contains(t)) continue;
            out.add(t);
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

        Map<Character, Integer> initialsMultiset = new LinkedHashMap<>();
        for (String token : fullParts) initialsMultiset.merge(token.charAt(0), 1, Integer::sum);

        for (String abbrev : candidateTokens) {
            if (abbrev == null || abbrev.isEmpty()) continue;
            // Two-character multi-token abbreviations collide too often unless they match the full token count.
            if (abbrev.length() < 2 || abbrev.length() > 6) continue;

            if (candidateTokens.length > 1 && fullParts.size() > 1
                    && abbrev.length() < 3 && abbrev.length() != fullParts.size()) continue;

            Map<Character, Integer> abbrevMultiset = new LinkedHashMap<>();
            for (char c : abbrev.toCharArray()) abbrevMultiset.merge(c, 1, Integer::sum);

            // Multiset initial matching preserves repeated initials in concepts with duplicate leading letters.
            boolean initialsMatch = true;
            for (Map.Entry<Character, Integer> e : abbrevMultiset.entrySet()) {
                if (initialsMultiset.getOrDefault(e.getKey(), 0) < e.getValue()) {
                    initialsMatch = false;
                    break;
                }
            }
            if (initialsMatch) return true;

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

    private static String sanitizeUnionName(String raw) {
        String s = StringUtil.safeTrim(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }
}
