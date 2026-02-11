package org.taniwha.util;

public final class MappingMathUtil {
    private MappingMathUtil() {}

    public static double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0.0;
        for (int i = 0; i < n; i++) dot += (double) a[i] * (double) b[i];
        return dot;
    }

    public static void l2NormalizeInPlace(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += (double) x * (double) x;
        double norm = Math.sqrt(sum);
        if (norm < 1e-12) return;
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
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
