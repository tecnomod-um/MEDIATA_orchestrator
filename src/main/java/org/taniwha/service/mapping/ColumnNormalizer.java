package org.taniwha.service.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.model.LearnedNoise;
import org.taniwha.util.NormalizationUtil;
import org.taniwha.util.StringUtil;

import java.util.*;

// Learns structural noise and normalizes raw column names into comparable concepts.
class ColumnNormalizer {

    private static final Logger logger = LoggerFactory.getLogger(ColumnNormalizer.class);

    private static final int MIN_COMPOUND_WORD_LENGTH = 8;
    private static final int MIN_SUBTOKEN_LENGTH = 3;

    private final MappingServiceSettings settings;

    ColumnNormalizer(MappingServiceSettings settings) {
        this.settings = settings;
    }

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

        for (Map.Entry<String, Integer> e : suffixCount.entrySet()) {
            // Very frequent short suffixes are usually export noise rather than semantic signal.
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

    List<String> tokenizeName(String rawColumn) {
        String s0 = StringUtil.safeTrim(rawColumn);
        if (s0.isEmpty()) return Collections.emptyList();

        String s = NormalizationUtil.splitCamelStrong(s0);
        s = s.toLowerCase(Locale.ROOT);

        s = s.replaceAll("([a-z])([0-9])", "$1 $2");
        s = s.replaceAll("([0-9])([a-z])", "$1 $2");

        String[] parts = s.split("[^a-z0-9]+");
        List<String> toks = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (t.matches("\\d+")) continue;
            if (t.length() == 1) continue;
            toks.add(t);
        }
        return toks;
    }

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

        if (kept.size() == 1 && kept.get(0).length() >= MIN_COMPOUND_WORD_LENGTH
                && noise != null && noise.df() != null) {
            // Only split fused tokens when both halves already look like known column vocabulary.
            List<String> split = trySplitCompound(kept.get(0), noise.df());
            if (split != null) kept = split;
        }

        return String.join(" ", kept).replaceAll("\\s+", " ").trim();
    }

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
