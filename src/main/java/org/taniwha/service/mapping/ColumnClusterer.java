package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.model.ColCluster;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.util.MappingMathUtil;
import org.taniwha.util.StringUtil;

import java.util.*;

/**
 * Groups {@link EmbeddedColumn} instances into conceptually similar clusters using cosine
 * similarity with structural-evidence guards (token Jaccard + abbreviation detection).
 * <p>
 * Pure domain-level semantic similarity alone is <em>not</em> sufficient for a merge —
 * structural evidence (shared tokens or abbreviation pair) is required to prevent distinct
 * clinical concepts from collapsing into one cluster.
 * </p>
 * <p>
 * Extracted from {@link MappingService} for maintainability.
 * </p>
 */
class ColumnClusterer {

    private static final Logger logger = LoggerFactory.getLogger(ColumnClusterer.class);

    /**
     * Grammatical inflection suffixes used in the single-token abbreviation check.
     * When a candidate token is a strict prefix of a single-token full concept we only
     * recognise it as an abbreviation when the remainder is a standard English morphological
     * suffix, NOT a compound-word second part (e.g. "fim" in "totalfim").
     */
    private static final Set<String> GRAMMATICAL_SUFFIXES = Set.of("ing", "ed", "er", "ers", "s", "es");

    private final MappingServiceSettings settings;

    ColumnClusterer(MappingServiceSettings settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------
    // Clustering
    // ------------------------------------------------------------------

    /**
     * Clusters the given columns so that conceptually synonymous columns (from different files)
     * end up in the same {@link ColCluster}.
     */
    List<ColCluster> clusterColumns(List<EmbeddedColumn> all) {
        // Initial grouping by normalised concept key
        Map<String, ColCluster> byConcept = new LinkedHashMap<>();
        for (EmbeddedColumn c : all) {
            String key = sanitizeUnionName(StringUtil.safeTrim(c.concept).toLowerCase(Locale.ROOT));
            if (key.isEmpty()) key = "__empty__";
            byConcept.computeIfAbsent(key, k -> new ColCluster()).add(c);
        }

        List<ColCluster> clusters = new ArrayList<>(byConcept.values());
        List<ColCluster> merged = new ArrayList<>();

        for (ColCluster col : clusters) {
            // Set the representative concept before the inner loop so conceptTokenJaccard
            // has a non-empty string to work with for this cluster.
            col.representativeConcept = pickClusterRepresentativeConcept(col.cols);
            String colConcept = col.representativeConcept;

            ColCluster bestMatch = null;
            double bestMatchSim = -1;

            for (ColCluster cl : merged) {
                double sim = MappingMathUtil.cosine(col.centroid, cl.centroid);

                double jac = conceptTokenJaccard(col, cl);
                boolean hasTokenOverlap = jac >= 0.15;

                // Abbreviation check — only when token overlap is absent (avoids redundant work).
                boolean hasAbbrevPair = false;
                if (!hasTokenOverlap) {
                    for (EmbeddedColumn clCol : cl.cols) {
                        if (isAbbreviationPair(colConcept, clCol.concept)) {
                            hasAbbrevPair = true;
                            break;
                        }
                    }
                }

                // Require structural evidence to prevent pure domain-level PubMedBERT similarity
                // from merging distinct clinical concepts like "toilet" and "bowel".
                if (!(hasTokenOverlap || hasAbbrevPair)) continue;

                logger.debug("[CLUSTER] '{}' vs '{}': sim={}, jac={}, abbrev={}",
                        colConcept, cl.representativeConcept, sim, jac, hasAbbrevPair);

                if (sim >= settings.columnClusterThreshold() && sim > bestMatchSim) {
                    bestMatchSim = sim;
                    bestMatch = cl;
                }
            }

            if (bestMatch != null) {
                for (EmbeddedColumn r : col.cols) bestMatch.add(r);
                // Keep the representative concept current after the cluster grows.
                bestMatch.representativeConcept = pickClusterRepresentativeConcept(bestMatch.cols);
            } else {
                merged.add(col);
            }
        }

        // Final pass — safety net to ensure every cluster has a representative concept set.
        for (ColCluster c : merged) {
            if (c.representativeConcept == null || c.representativeConcept.isEmpty()) {
                c.representativeConcept = pickClusterRepresentativeConcept(c.cols);
            }
        }

        return merged;
    }

    // ------------------------------------------------------------------
    // Representative-concept selection
    // ------------------------------------------------------------------

    /**
     * Picks the shortest (and lexicographically earliest on a tie) non-empty concept string
     * among the cluster members as the cluster's representative concept.
     */
    String pickClusterRepresentativeConcept(List<EmbeddedColumn> cols) {
        String best = null;
        int bestLen = Integer.MAX_VALUE;

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

        if (best == null) best = cols.get(0).concept;
        return best;
    }

    // ------------------------------------------------------------------
    // Token Jaccard
    // ------------------------------------------------------------------

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
            if (t.isEmpty() || t.matches("\\d+") || t.length() <= 1) continue;
            out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Abbreviation detection
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} if one concept appears to be an abbreviation or acronym of the other.
     * Handles three cases:
     * <ol>
     *   <li>Initialism (order-insensitive): {@code "sbp"} ↔ {@code "blood pressure systolic"}</li>
     *   <li>Prefix: {@code "cr"} ↔ {@code "serum creatinine"}</li>
     *   <li>Suffix-initialism: {@code "egfr"} ↔ {@code "glomerular filtration rate"}</li>
     * </ol>
     */
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

        // Single-token full concept (e.g. "grooming", "toileting", "dressing"):
        // detect when any candidate token is a strict prefix of that token.
        if (fullParts.size() == 1) {
            String singleToken = fullParts.get(0);
            if (singleToken.length() < 4) return false;
            for (String abbrev : candidateTokens) {
                if (abbrev == null || abbrev.length() < 3) continue;
                if (singleToken.length() > abbrev.length() && singleToken.startsWith(abbrev)) {
                    String remainder = singleToken.substring(abbrev.length());
                    if (GRAMMATICAL_SUFFIXES.contains(remainder)) return true;
                }
            }
            return false;
        }
        if (fullParts.size() < 2) return false;

        // Build initials multiset: char → how many full-concept tokens start with that char
        Map<Character, Integer> initialsMultiset = new LinkedHashMap<>();
        for (String token : fullParts) initialsMultiset.merge(token.charAt(0), 1, Integer::sum);

        for (String abbrev : candidateTokens) {
            if (abbrev == null || abbrev.isEmpty()) continue;
            if (abbrev.length() < 2 || abbrev.length() > 6) continue;

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
            for (String token : fullParts) {
                if (token.length() > abbrev.length() && token.startsWith(abbrev)) return true;
            }

            // Case 3: Suffix-initialism — for prefixed abbreviations like eGFR → GFR
            for (int start = 1; start <= abbrev.length() - 3; start++) {
                String suffix = abbrev.substring(start);
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

    // ------------------------------------------------------------------
    // Utility — intentionally duplicated from MappingAssembler to avoid a
    // cross-dependency between two package-private classes.
    // ------------------------------------------------------------------

    private static String sanitizeUnionName(String raw) {
        String s = StringUtil.safeTrim(raw);
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }
}
