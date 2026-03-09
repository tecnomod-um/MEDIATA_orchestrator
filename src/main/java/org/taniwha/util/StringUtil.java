package org.taniwha.util;

import java.util.Collections;
import java.util.List;

public final class StringUtil {
    private StringUtil() {}

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    public static List<String> safeList(List<String> xs) {
        return xs == null ? Collections.emptyList() : xs;
    }

    public static String groupKey(String nodeId, String fileName, String groupColumn) {
        return safe(nodeId) + "::" + safe(fileName) + "::" + safe(groupColumn);
    }

    public static boolean containsAnyToken(String normalizedText, String... tokens) {
        String x = safe(normalizedText);
        for (String t : tokens) {
            String tt = safe(t).toLowerCase(java.util.Locale.ROOT);
            if (tt.isEmpty()) continue;
            if (x.contains(tt)) return true;
        }
        return false;
    }

    public static String stripTrailingZeros(Double x) {
        if (x == null) return "";
        if (Math.abs(x - Math.rint(x)) < 1e-9) return String.valueOf((long) Math.rint(x));
        String s = String.valueOf(x);
        if (s.contains(".")) {
            while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Converts a raw concept/field name (e.g. {@code "age_at_injury"}) into a
     * human-readable phrase (e.g. {@code "age at injury"}) by replacing underscores
     * and hyphens with spaces and lower-casing the result.
     */
    public static String humanize(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "data element";
        return raw.trim()
                .replace("_", " ")
                .replace("-", " ")
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** Returns {@code s} with its first character upper-cased; returns {@code s} unchanged if blank. */
    public static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
