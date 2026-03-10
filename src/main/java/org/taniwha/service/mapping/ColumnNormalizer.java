package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.model.LearnedNoise;
import org.taniwha.util.NormalizationUtil;
import org.taniwha.util.StringUtil;

import java.util.*;

/**
 * Handles column-name tokenisation, structural-noise learning, and concept normalisation.
 * <p>
 * Extracted from {@link MappingService} for maintainability.
 * </p>
 */
class ColumnNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(ColumnNormalizer.class);

    /** Minimum length of a single-token concept before compound splitting is attempted. */
    private static final int MIN_COMPOUND_WORD_LENGTH = 8;
    /** Minimum character length for each half of a compound split (left and right). */
    private static final int MIN_SUBTOKEN_LENGTH = 3;

    private final MappingServiceSettings settings;

    ColumnNormalizer(MappingServiceSettings settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------
    // Noise learning
    // ------------------------------------------------------------------

    /**
     * Inspects all raw column names in the request and derives global + suffix stop-token sets
     * that should be stripped before embedding.
     */
    LearnedNoise learnNoiseFromRequest(List<String> rawColumnNames) {
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> suffixCount = new HashMap<>();

        int n = 0;
        for (String rawColumnName : rawColumnNames == null ? List.<String>of() : rawColumnNames) {
            String name = StringUtil.safeTrim(rawColumnName);
            if (name.isEmpty()) continue;
            List<String> tokens = tokenizeName(name);
            if (tokens.isEmpty()) continue;
            n++;

            Set<String> uniq = new HashSet<>(tokens);
            for (String t : uniq) {
                if (t == null || t.isEmpty()) continue;
                df.put(t, df.getOrDefault(t, 0) + 1);
            }

            String last = tokens.get(tokens.size() - 1);
            if (last != null && !last.isEmpty()) {
                suffixCount.put(last, suffixCount.getOrDefault(last, 0) + 1);
            }
        }

        Set<String> globalCandidates = new HashSet<>();
        Set<String> suffixCandidates = new HashSet<>();

        for (Map.Entry<String, Integer> e : df.entrySet()) {
            String t = e.getKey();
            int c = e.getValue();
            if (c >= settings.noiseDocumentFrequencyMinCount()) globalCandidates.add(t);
            else if (n > 0 && (c / (double) n) >= settings.noiseDocumentFrequencyFraction()) globalCandidates.add(t);
        }

        for (Map.Entry<String, Integer> e : suffixCount.entrySet()) {
            if (e.getValue() >= settings.suffixNoiseMinCount()) suffixCandidates.add(e.getKey());
        }

        // High-frequency suffix override: a token appearing as the last token in >= 8 columns
        // is almost certainly a dataset-specific technical marker (e.g. "bart" in Barthel ADL
        // column names: BowelBART1, BladderBART1, BathingBART1 … 16 occurrences).  Strip it
        // regardless of whether it contains vowels — the vowel heuristic only applies to the
        // low-count case.
        for (Map.Entry<String, Integer> e : suffixCount.entrySet()) {
            if (e.getValue() >= 8 && e.getKey().length() <= 5) suffixCandidates.add(e.getKey());
        }

        Set<String> globalStop = new HashSet<>();
        for (String t : globalCandidates) {
            if (isStructuralToken(t)) globalStop.add(t);
        }

        Set<String> suffixStop = new HashSet<>();
        for (String t : suffixCandidates) {
            if (isStructuralSuffixToken(t)) suffixStop.add(t);
        }
        // High-frequency suffix tokens added above are included unconditionally — they are
        // structurally meaningful noise even with vowels.
        for (Map.Entry<String, Integer> e : suffixCount.entrySet()) {
            if (e.getValue() >= 8 && e.getKey().length() <= 5) suffixStop.add(e.getKey());
        }

        if (n <= 6 && globalStop.size() > 3) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>();
            for (String t : globalStop) {
                entries.add(Map.entry(t, df.getOrDefault(t, 0)));
            }
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            Set<String> keep = new HashSet<>();
            for (int i = 0; i < Math.min(3, entries.size()); i++) keep.add(entries.get(i).getKey());
            globalStop.retainAll(keep);
        }

        return new LearnedNoise(globalStop, suffixStop, df, n);
    }

    // ------------------------------------------------------------------
    // Tokenisation
    // ------------------------------------------------------------------

    List<String> tokenizeName(String rawColumn) {
        String s0 = StringUtil.safeTrim(rawColumn);
        if (s0.isEmpty()) return Collections.emptyList();

        String s = NormalizationUtil.splitCamelStrong(s0);
        s = s.toLowerCase(Locale.ROOT);

        // Split alpha<->digit boundaries before non-alnum split, so "bart2" becomes "bart 2"
        s = s.replaceAll("([a-z])([0-9])", "$1 $2");
        s = s.replaceAll("([0-9])([a-z])", "$1 $2");

        String[] parts = s.split("[^a-z0-9]+");
        List<String> toks = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d+")) continue;            // drop pure digits
            if (t.length() == 1) continue;              // drop single-letter tokens
            toks.add(t);
        }
        return toks;
    }

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    /**
     * Strips learned noise tokens from a raw column name and returns the cleaned concept string
     * suitable for embedding.
     */
    String normalizeRawColumn(String rawColumn, LearnedNoise noise) {
        List<String> tokens = tokenizeName(rawColumn);
        if (tokens.isEmpty()) return "";
        List<String> kept = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (noise != null) {
                if (noise.globalStopTokens().contains(t)) {
                    logger.debug("[Noise] Removed token '{}' from column '{}' (structural global)", t, rawColumn);
                    continue;
                }
                if (i == tokens.size() - 1 && noise.suffixStopTokens().contains(t)) {
                    logger.debug("[Noise] Removed token '{}' from column '{}' (structural suffix)", t, rawColumn);
                    continue;
                }
            }
            kept.add(t);
        }
        if (kept.isEmpty()) kept = tokens;

        // Compound-word split: if a single long token survives (e.g. "ageinjury",
        // "totalbarthel"), try to split it into two known vocabulary sub-tokens.
        // This lets all-caps compound columns ("AGEINJURY", "TOTALBARTHEL") align
        // with their space-separated counterparts ("Age at injury", "TOTBarthel1").
        if (kept.size() == 1 && kept.get(0).length() >= MIN_COMPOUND_WORD_LENGTH
                && noise != null && noise.df() != null) {
            List<String> split = trySplitCompound(kept.get(0), noise.df());
            if (split != null) kept = split;
        }

        return String.join(" ", kept).replaceAll("\\s+", " ").trim();
    }

    /**
     * Tries to split a single compound token into two known vocabulary sub-tokens.
     * Both the left and right part must appear in the request's token vocabulary (df >= 1).
     * Returns {@code null} if no valid split is found.
     * <p>
     * Example: {@code "ageinjury"} → {@code ["age", "injury"]}  (when both are in other columns)
     * </p>
     */
    List<String> trySplitCompound(String token, Map<String, Integer> vocab) {
        int len = token.length();
        for (int i = MIN_SUBTOKEN_LENGTH; i <= len - MIN_SUBTOKEN_LENGTH; i++) {
            String left  = token.substring(0, i);
            String right = token.substring(i);
            if (vocab.containsKey(left) && vocab.containsKey(right)) {
                return List.of(left, right);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Structural-token helpers
    // ------------------------------------------------------------------

    private boolean isStructuralToken(String t) {
        if (t == null) return false;
        String x = t.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return false;
        if (x.matches("\\d+")) return true;
        if (x.length() == 1) return true;
        if (x.length() == 2) return !containsVowel(x);
        return x.equals("id") || x.equals("code") || x.equals("name") || x.equals("value")
                || x.equals("flag") || x.equals("indicator") || x.equals("status") || x.equals("type");
    }

    private boolean isStructuralSuffixToken(String t) {
        if (t == null) return false;
        String x = t.trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return false;
        if (x.matches("\\d+")) return true;
        if (x.length() == 1) return true;
        // Short vowel-less suffix → likely a dataset technical marker
        return x.length() <= 4 && !containsVowel(x);
    }

    private boolean containsVowel(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') return true;
        }
        return false;
    }
}
