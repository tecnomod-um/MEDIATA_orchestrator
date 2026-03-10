package org.taniwha.util;

public final class MappingMathUtil {
    private MappingMathUtil() {}

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null) return 0.0;
        int n = Math.min(a.length, b.length);
        if (n == 0) return 0.0;

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < n; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            normA += x * x;
            normB += y * y;
        }
        if (normA < 1e-12 || normB < 1e-12) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static int clamp(int x, int lo, int hi) {
        if (x < lo) return lo;
        if (x > hi) return hi;
        return x;
    }

    public static int safeInt(String s, int def) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); }
        catch (Exception ignore) { return def; }
    }
}
