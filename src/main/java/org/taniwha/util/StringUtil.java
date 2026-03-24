package org.taniwha.util;

import java.util.Collections;
import java.util.List;

public final class StringUtil {
    private StringUtil() {}

    public static List<String> safeList(List<String> xs) {
        return xs == null ? Collections.emptyList() : xs;
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    public static String groupKey(String nodeId, String fileName, String groupColumn) {
        return safe(nodeId) + "::" + safe(fileName) + "::" + safe(groupColumn);
    }

    public static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
