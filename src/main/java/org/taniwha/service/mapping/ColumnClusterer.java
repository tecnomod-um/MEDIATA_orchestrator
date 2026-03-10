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
 * similarity with structural-evidence guards (token Jaccard + abbreviation detection +
 * value-embedding similarity).
 * <p>
 * Pure domain-level semantic similarity alone is <em>not</em> sufficient for a merge —
 * structural evidence (shared tokens, abbreviation pair, or similar value embeddings) is
 * required to prevent distinct clinical concepts from collapsing into one cluster.
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

    /**
     * Generic temporal/unit/qualifier tokens that should not contribute to Jaccard token overlap.
     * <ul>
     *   <li>Time-unit tokens (e.g. {@code "days"}): two columns sharing only {@code "days"}
     *       (e.g. {@code "LOS (days)"} range 2-56 vs {@code "TSI to admission (days)"} range
     *       203-7726) measure entirely different durations and must not merge on that alone.</li>
     *   <li>{@code "total"}: a generic measurement qualifier that appears in many unrelated
     *       clinical scales (e.g. {@code "TOTAL MOTOR"} and {@code "TOTAL COGNITIU"} are
     *       different FIM subscales; they must not merge just because both names start with
     *       {@code "total"}).</li>
     * </ul>
     */
    private static final Set<String> JACCARD_STOP_TOKENS =
            Set.of("total", "days", "weeks", "months", "years", "hours", "minutes");

    /**
     * Minimum cosine similarity between two clusters' char-n-gram value centroids required to
     * treat value-level character similarity as structural alignment evidence.
     */
    private static final double VALUE_EMBEDDING_THRESHOLD = 0.30;

    /**
     * When two clusters' value centroids have cosine similarity at or above this bound their
     * value distributions are considered <em>identical</em> (e.g. two different binary
     * {@code "yes/no"} columns) and value-level evidence is suppressed to avoid spurious merges.
     */
    private static final double VALUE_EMBEDDING_IDENTICAL = 0.99;

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

                // Value-embedding similarity check — catches columns whose concept names are too
                // generic (e.g. "Type") but whose categorical values embed close to another
                // cluster's values (e.g. "Ischemic"/"Hemorrhagic" ≈ "Isch"/"Hem").
                boolean hasValueEvidence = false;
                if (!hasTokenOverlap && !hasAbbrevPair) {
                    hasValueEvidence = hasValueEmbeddingSimilarity(col, cl);
                }

                // Require structural evidence to prevent pure domain-level PubMedBERT similarity
                // from merging distinct clinical concepts like "toilet" and "bowel".
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
     * Structural single-word concepts that carry no domain semantics and should be
     * deprioritised when selecting a cluster's representative concept.
     * Matches the explicit structural-word list in {@link ColumnNormalizer#isStructuralToken}.
     */
    private static final Set<String> STRUCTURAL_CONCEPT_WORDS =
            Set.of("id", "code", "name", "value", "flag", "indicator", "status", "type");

    /**
     * Returns {@code true} when {@code concept} is a single generic structural token
     * (e.g. {@code "type"}, {@code "value"}) that carries no domain semantics.
     * Such concepts are deprioritised in {@link #pickClusterRepresentativeConcept}.
     */
    private boolean isStructuralSingleTokenConcept(String concept) {
        Set<String> tokens = conceptTokens(concept);
        return tokens.size() == 1 && STRUCTURAL_CONCEPT_WORDS.contains(tokens.iterator().next());
    }

    /**
     * Picks the most informative (and shortest on a tie) non-empty concept string among
     * the cluster members as the cluster's representative concept.
     * <p>
     * Generic structural single-token concepts (e.g. {@code "type"}) are skipped in the
     * first pass and only used as a fallback when every member concept is structural.
     * This ensures that when a column named {@code "Type"} merges with one named
     * {@code "Etiology (Isch/Hem)"}, the representative is {@code "etiology isch hem"}
     * rather than the uninformative {@code "type"}.
     * </p>
     */
    String pickClusterRepresentativeConcept(List<EmbeddedColumn> cols) {
        // First pass: prefer the shortest non-structural concept.
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

        // Second pass: if every concept is structural, fall back to shortest overall.
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
            if (t.isEmpty() || t.matches("\\d+") || t.length() <= 1
                    || JACCARD_STOP_TOKENS.contains(t)) continue;
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

            // Multi-token guard for Case 1 (initialism): in a multi-token → multi-token
            // context, tokens shorter than 3 characters are typically prepositions or articles
            // ("at", "to", "by") and must not act as initialism abbreviations.  Without this
            // guard "at" in "age at injury" would incorrectly match the initials of
            // "tsi to admission days" (which share 'a' and 't').
            if (candidateTokens.length > 1 && fullParts.size() > 1 && abbrev.length() < 3) continue;

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

            // Case 2: Prefix — abbreviation is a STRICT prefix of a full-concept token.
            // Multi-token guard: when BOTH concepts have more than one token, every OTHER
            // candidate token must also match some remaining full token (by equality or prefix).
            // This prevents "tot barthel" from abbreviating "total motor" just because "tot" is
            // a strict prefix of "total" — "barthel" has no match in ["motor"].
            for (String token : fullParts) {
                if (token.length() > abbrev.length() && token.startsWith(abbrev)) {
                    if (candidateTokens.length > 1 && fullParts.size() > 1) {
                        if (remainingTokensMatch(abbrev, candidateTokens, token, fullParts)) return true;
                    } else {
                        return true;
                    }
                }
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

    /**
     * Returns {@code true} when every candidate token other than {@code matchedCandidateToken}
     * has a matching counterpart among the full-concept tokens other than
     * {@code matchedFullToken}. Matching is by equality or strict prefix relationship in either
     * direction.
     * <p>
     * Used by the Case 2 (prefix) multi-token guard in
     * {@link #looksLikeAbbreviationOf(String, String)} to prevent {@code "tot barthel"} from
     * abbreviating {@code "total motor"} just because {@code "tot"} is a prefix of
     * {@code "total"} — the other token {@code "barthel"} has no match in {@code ["motor"]}.
     * </p>
     */
    private static boolean remainingTokensMatch(
            String matchedCandidateToken, String[] candidateTokens,
            String matchedFullToken,      List<String> fullParts) {
        // Build the set of remaining full tokens (exclude the already-matched full token).
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

    // ------------------------------------------------------------------
    // Value-embedding evidence
    // ------------------------------------------------------------------

    /**
     * Returns {@code true} when the char-n-gram value centroids of the two clusters have
     * sufficient similarity to serve as structural alignment evidence, but are not identical.
     * <p>
     * <strong>Similarity ≥ {@link #VALUE_EMBEDDING_THRESHOLD} (0.30)</strong>: the clusters'
     * value domains share enough character-level features to suggest the same underlying concept
     * encoded differently — e.g. {@code "Ischemic"/"Hemorrhagic"} vs {@code "Hem"/"Isch"}.
     * The 3-gram {@code "hem"} appears in {@code "ischemic"} (positions 3-5) and in
     * {@code "hemorrhagic"}, and is also the full word in the second column; similarly
     * {@code "isc"}/{@code "sch"} appear in both sides.
     * </p>
     * <p>
     * <strong>Similarity &lt; {@link #VALUE_EMBEDDING_IDENTICAL} (0.99)</strong>: vectors that
     * are practically identical indicate two columns with the <em>same</em> value distribution
     * (e.g. two separate binary {@code "yes/no"} clinical variables such as
     * {@code "diabetes"} and {@code "hypertension"}).  Those must not be merged even when their
     * PubMedBERT combined-embedding similarity is high.
     * </p>
     * <p>
     * Only clusters where at least one member carries a non-null {@link EmbeddedColumn#valueVec}
     * participate; numeric/date columns return {@code null} from {@link org.taniwha.util.ValueVectorUtil}
     * and are therefore excluded via {@code valueCentroid == null}.
     * </p>
     */
    private boolean hasValueEmbeddingSimilarity(ColCluster a, ColCluster b) {
        if (a.valueCentroid == null || b.valueCentroid == null) return false;
        double sim = MappingMathUtil.cosine(a.valueCentroid, b.valueCentroid);
        logger.debug("[CLUSTER] value-ngram sim '{}' vs '{}': {}", a.representativeConcept, b.representativeConcept, sim);
        if (sim >= VALUE_EMBEDDING_IDENTICAL) return false;
        return sim >= VALUE_EMBEDDING_THRESHOLD;
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
