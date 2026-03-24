package org.taniwha.util;

import java.util.List;
import java.util.Locale;

/**
 * Builds character n-gram hash vectors from column value lists.
 *
 * <p>Unlike the PubMedBERT embeddings produced by {@code EmbeddingService}, these vectors
 * capture <em>character-level</em> similarity: abbreviated values such as {@code "Isch"} share
 * overlapping n-grams ({@code isc}, {@code sch}) with their full forms
 * ({@code "Ischemic"} contains the same 3-grams), while purely identical value distributions
 * (e.g. two binary {@code "yes/no"} columns) produce cosine similarity 1.0 which is
 * explicitly excluded by the caller as an identical-domain guard.
 * </p>
 *
 * <p>Used by {@link org.taniwha.service.mapping.ColumnClusterer} to decide whether two
 * clusters whose concept names share no token overlap can still be merged because their
 * categorical value domains encode the same underlying concept.</p>
 */
public final class ValueVectorUtil {

    /** Dimension of the hash vector — must be a power of 2 for the bitwise-AND modulo. */
    public static final int DIM = 2048;

    private static final int MAX_VALUES = 15;

    private ValueVectorUtil() {}

    /**
     * Returns a normalised char-n-gram hash vector for the categorical values in
     * {@code rawValues}, or {@code null} when {@code rawValues} contains no categorical values
     * (i.e. all entries are numeric/date type markers that
     * {@link NormalizationUtil#normalizeValue} maps to the empty string).
     */
    public static float[] build(List<String> rawValues) {
        if (rawValues == null) return null;

        float[] v = new float[DIM];
        int count = 0;

        for (String raw : rawValues) {
            if (count >= MAX_VALUES) break;

            String s = NormalizationUtil.normalizeValue(raw);
            if (s.isEmpty()) { count++; continue; }

            // Word-level feature: hash each alphabetic/digit token of length >= 2.
            for (String tok : s.split("[^a-z0-9]+")) {
                String t = tok.trim();
                if (t.length() < 2) continue;
                int idx = (int) (NormalizationUtil.fnv1a32(t) & (DIM - 1));
                v[idx] += 1.0f;
            }

            // Character n-grams (applied to the full normalised value string):
            // n=3 carries the highest weight (0.70) so that "isch" (3-grams: isc, sch) shares
            // features with "ischemic" (same 3-grams), and "hem" shares the 3-gram "hem" with
            // both "ischemic" (positions 3-5) and "hemorrhagic" (positions 0-2).
            // n=2 (weight 0.30) and n=4 (weight 0.55) add supporting signal.
            addNgrams(v, s, 2, 0.30f);
            addNgrams(v, s, 3, 0.70f);
            addNgrams(v, s, 4, 0.55f);
            count++;
        }

        double normSq = 0.0;
        for (float x : v) normSq += (double) x * (double) x;
        if (normSq < 1e-12) return null;

        double norm = Math.sqrt(normSq);
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        return v;
    }

    private static void addNgrams(float[] v, String s, int n, float weight) {
        if (s.length() < n) return;
        for (int i = 0; i <= s.length() - n; i++) {
            String g = s.substring(i, i + n);
            int idx = (int) (NormalizationUtil.fnv1a32(g) & (DIM - 1));
            v[idx] += weight;
        }
    }
}
