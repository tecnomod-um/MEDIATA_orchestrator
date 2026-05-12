package org.taniwha.util;

import java.util.List;

// Builds lightweight hashed value-domain vectors for cosine similarity comparisons.
public final class ValueVectorUtil {

    // Power-of-two dimensions keep feature hashing fast and deterministic.
    public static final int DIM = 2048;

    private static final int MAX_VALUES = 15;

    private ValueVectorUtil() {}

    public static float[] build(List<String> rawValues) {
        if (rawValues == null) return null;

        float[] v = new float[DIM];
        int count = 0;

        for (String raw : rawValues) {
            if (count >= MAX_VALUES) break;

            String s = NormalizationUtil.normalizeValue(raw);
            if (s.isEmpty()) { count++; continue; }

            for (String tok : s.split("[^a-z0-9]+")) {
                String t = tok.trim();
                if (t.length() < 2) continue;
                // Whole-token hashes preserve exact lexical overlap between small value vocabularies.
                int idx = (int) (NormalizationUtil.fnv1a32(t) & (DIM - 1));
                v[idx] += 1.0f;
            }

            // Character n-grams keep clipped labels and close spelling variants near each other.
            addNgrams(v, s, 2, 0.30f);
            addNgrams(v, s, 3, 0.70f);
            addNgrams(v, s, 4, 0.55f);
            count++;
        }

        double normSq = 0.0;
        for (float x : v) normSq += (double) x * (double) x;
        if (normSq < 1e-12) return null;

        // L2 normalization makes cosine scores comparable across columns with different sample counts.
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
