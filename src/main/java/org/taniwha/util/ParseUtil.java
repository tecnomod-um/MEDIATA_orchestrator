package org.taniwha.util;

import java.time.Instant;

import java.util.Date;
import java.util.List;

import org.taniwha.model.ColStats;

public final class ParseUtil {
    private ParseUtil() {}

    public static Integer tryParseInt(String s) {
        try {
            String x = StringUtil.safeTrim(s);
            if (x.isEmpty()) return null;
            return Integer.parseInt(x);
        } catch (Exception ignore) {
            return null;
        }
    }

    public static Double tryParseDouble(String s) {
        try {
            String x = StringUtil.safeTrim(s);
            if (x.isEmpty()) return null;
            return Double.parseDouble(x);
        } catch (Exception ignore) {
            return null;
        }
    }

    public static Long tryParseDateMs(String s) {
        String x = StringUtil.safeTrim(s);
        if (x.isEmpty()) return null;

        try {
            return Instant.parse(x).toEpochMilli();
        } catch (Exception ignored) {
            return tryParseLegacyDateMs(x);
        }
    }

    private static Long tryParseLegacyDateMs(String x) {
        try {
            return Date.parse(x);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ColStats parseColStats(List<String> rawValues) {
        ColStats s = new ColStats();
        if (rawValues == null) return s;

        for (String rv : rawValues) {
            String t = StringUtil.safeTrim(rv);
            if (t.isEmpty()) continue;

            String low = t.toLowerCase(java.util.Locale.ROOT);

            if ("integer".equals(low)) s.hasIntegerMarker = true;
            if ("double".equals(low)) s.hasDoubleMarker = true;
            if ("date".equals(low)) s.hasDateMarker = true;

            if (low.startsWith("min:")) {
                Double v = tryParseDouble(low.substring("min:".length()));
                if (v != null) s.numMin = v;
            } else if (low.startsWith("max:")) {
                Double v = tryParseDouble(low.substring("max:".length()));
                if (v != null) s.numMax = v;
            } else if (low.startsWith("earliest:")) {
                Long ms = tryParseDateMs(low.substring("earliest:".length()));
                if (ms != null) s.dateMinMs = ms;
            } else if (low.startsWith("latest:")) {
                Long ms = tryParseDateMs(low.substring("latest:".length()));
                if (ms != null) s.dateMaxMs = ms;
            } else if (low.startsWith("step:")) {
                Integer step = tryParseInt(low.substring("step:".length()));
                if (step != null && step > 0) s.stepHint = step;
            }
        }
        return s;
    }
}