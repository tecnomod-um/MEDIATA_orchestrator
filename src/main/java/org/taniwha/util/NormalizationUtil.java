package org.taniwha.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class NormalizationUtil {
    private NormalizationUtil() {}

    public static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        t = splitCamelStrong(t);
        t = t.toLowerCase(Locale.ROOT);
        t = t.replace('_', ' ').replace('-', ' ');
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    /**
     * Strong camel splitter:
     * - Upper -> UpperLower boundary (e.g. "BToilet" => "B Toilet")
     * - lower/digit -> Upper (e.g. "toiletUse" => "toilet Use")
     */
    public static String splitCamelStrong(String s) {
        if (s == null) return "";
        String x = s;
        x = x.replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ");
        x = x.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        return x;
    }

    /**
     * Normalizes a value token, and drops type/range/date marker rows that you embed into the samples.
     */
    public static String normalizeValue(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);

        if (t.startsWith("min:") || t.startsWith("max:") || t.startsWith("earliest:") || t.startsWith("latest:")) return "";
        if ("integer".equals(t) || "double".equals(t) || "date".equals(t)) return "";

        t = t.replace('_', ' ').replace('-', ' ');
        t = t.replaceAll("\\s+", " ");
        return t;
    }

    public static long fnv1a32(String s) {
        if (s == null) s = "";
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        long h = 0x811c9dc5L;
        for (byte datum : data) {
            h ^= (datum & 0xff);
            h *= 0x01000193L;
            h &= 0xffffffffL;
        }
        return h;
    }
}
