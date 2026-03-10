package org.taniwha.service;

import org.springframework.stereotype.Service;
import org.taniwha.util.NormalizationUtil;
import org.taniwha.util.ParseUtil;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class EmbeddingService {

    private static final int MAX_VALUES_FOR_EMBEDDING = 15;
    private static final int MAX_VALUES = 80;
    private static final int MAX_ENUM = 120;

    // Generic stem/prefix hints (no medical dictionary)
    private static final int STEM_MIN_SRC_LEN = 6; // only stem words that are "long enough"
    private static final int STEM_LEN = 4;
    private static final int MAX_STEM_HINTS = 8;

    private final EmbeddingsClient embeddingsClient;

    public EmbeddingService(EmbeddingsClient embeddingsClient) {
        this.embeddingsClient = embeddingsClient;
    }

    public float[] embedColumnWithValues(String columnName, List<String> values) {
        String cn = safe(columnName).trim();

        String type = "";
        Double min = null;
        Double max = null;

        List<String> kept = new ArrayList<>();
        if (values != null) {
            int count = 0;
            for (String raw : values) {
                if (count >= MAX_VALUES_FOR_EMBEDDING) break;

                String s0 = safe(raw).trim();
                if (s0.isEmpty()) { count++; continue; }

                String low = s0.toLowerCase(Locale.ROOT);

                if (low.equals("integer") || low.equals("double") || low.equals("date")) {
                    type = low;
                    count++;
                    continue;
                }

                if (low.startsWith("min:")) {
                    Double parsed = ParseUtil.tryParseDouble(low.substring(4).trim());
                    if (parsed != null) min = parsed;
                    count++;
                    continue;
                }

                if (low.startsWith("max:")) {
                    Double parsed = ParseUtil.tryParseDouble(low.substring(4).trim());
                    if (parsed != null) max = parsed;
                    count++;
                    continue;
                }

                if (low.startsWith("earliest:") || low.startsWith("latest:") || low.startsWith("step:")) {
                    count++;
                    continue;
                }

                String s = NormalizationUtil.normalizeValue(s0);
                if (s.isEmpty()) { count++; continue; }
                kept.add(s);
                count++;
            }
        }

        // Build stem hints from kept values (e.g., ischemic -> isch, hemorrhagic -> hemo)
        LinkedHashSet<String> stemHints = collectStemHints(kept);

        StringBuilder prompt = new StringBuilder();
        if (!cn.isEmpty()) prompt.append(cn);

        if (!type.isEmpty() || (min != null && max != null)) {
            if (prompt.length() > 0) prompt.append(" | ");
            if (!type.isEmpty()) prompt.append(type);
            if (min != null && max != null) {
                if (!type.isEmpty()) prompt.append(" ");
                prompt.append("range ").append(trimNum(min)).append("..").append(trimNum(max));
            }
        }

        if (!kept.isEmpty()) {
            if (prompt.length() > 0) prompt.append(" | ");
            int lim = Math.min(8, kept.size());
            for (int i = 0; i < lim; i++) {
                if (i > 0) prompt.append(", ");
                prompt.append(kept.get(i));
            }
        }

        // Add stem hints as an extra segment
        if (!stemHints.isEmpty()) {
            if (prompt.length() > 0) prompt.append(" | ");
            prompt.append("stem_hints ");
            int i = 0;
            for (String st : stemHints) {
                if (i++ > 0) prompt.append(", ");
                prompt.append(st);
                if (i >= MAX_STEM_HINTS) break;
            }
        }

        return embeddingsClient.embed(prompt.toString());
    }

    public float[] embedSchemaField(String fieldName, String type, List<String> enumVals) {
        StringBuilder prompt = new StringBuilder();

        String fn = safe(fieldName).trim();
        if (!fn.isEmpty()) prompt.append(fn);

        String ty = safe(type).trim();
        if (!ty.isEmpty()) {
            if (prompt.length() > 0) prompt.append(" (").append(ty).append(")");
            else prompt.append(ty);
        }

        List<String> kept = new ArrayList<>();
        if (enumVals != null && !enumVals.isEmpty()) {
            StringBuilder valueStr = new StringBuilder();
            int count = 0;

            for (String val : enumVals) {
                if (count >= MAX_VALUES_FOR_EMBEDDING) break;

                String s = NormalizationUtil.normalizeValue(val);
                if (s.isEmpty()) { count++; continue; }

                kept.add(s);

                if (valueStr.length() > 0) valueStr.append(", ");
                valueStr.append(s);
                count++;
            }

            if (valueStr.length() > 0) {
                if (prompt.length() > 0) prompt.append(": ");
                prompt.append(valueStr);
            }
        }

        // Stem hints for schema enums too
        LinkedHashSet<String> stemHints = collectStemHints(kept);
        if (!stemHints.isEmpty()) {
            if (prompt.length() > 0) prompt.append(" | ");
            prompt.append("stem_hints ");
            int i = 0;
            for (String st : stemHints) {
                if (i++ > 0) prompt.append(", ");
                prompt.append(st);
                if (i >= MAX_STEM_HINTS) break;
            }
        }

        return embeddingsClient.embed(prompt.toString());
    }

    public float[] embedName(String name) {
        String s = safe(name).trim();
        return embeddingsClient.embed(s);
    }

    public float[] embedValues(List<String> values) {
        if (values == null || values.isEmpty()) return embeddingsClient.embed("");

        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (String raw : values) {
            if (count >= MAX_VALUES) break;

            String s = NormalizationUtil.normalizeValue(raw);
            if (s.isEmpty()) { count++; continue; }

            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
            count++;
        }

        return embeddingsClient.embed(sb.toString());
    }

    public float[] embedEnum(List<String> enumVals, String typeHint) {
        StringBuilder sb = new StringBuilder();

        String th = safe(typeHint).trim();
        if (!th.isEmpty()) sb.append(th).append(": ");

        if (enumVals != null) {
            int count = 0;
            for (String raw : enumVals) {
                if (count >= MAX_ENUM) break;

                String s = NormalizationUtil.normalizeValue(raw);
                if (s.isEmpty()) { count++; continue; }

                if (count > 0) sb.append(", ");
                sb.append(s);
                count++;
            }
        }

        return embeddingsClient.embed(sb.toString());
    }

    public float[] embedSingleValue(String raw) {
        String s = NormalizationUtil.normalizeValue(raw);
        return embeddingsClient.embed(s);
    }

    private static String trimNum(Double d) {
        if (d == null) return "";
        double x = d;
        if (Math.abs(x - Math.rint(x)) < 1e-9) return String.valueOf((long) Math.rint(x));
        String s = String.valueOf(x);
        if (s.contains("E") || s.contains("e")) return s;
        int dot = s.indexOf('.');
        if (dot < 0) return s;
        int end = s.length();
        while (end > dot + 1 && s.charAt(end - 1) == '0') end--;
        if (end > dot && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    private static LinkedHashSet<String> collectStemHints(List<String> kept) {
        LinkedHashSet<String> stems = new LinkedHashSet<>();
        if (kept == null) return stems;

        for (String v : kept) {
            String st = stemOf(v);
            if (!st.isEmpty()) stems.add(st);
            if (stems.size() >= MAX_STEM_HINTS) break;
        }
        return stems;
    }

    private static String stemOf(String token) {
        if (token == null) return "";
        String t = baseToken(token).toLowerCase(Locale.ROOT);
        t = t.replaceAll("[^a-z]", "");
        if (t.length() < STEM_MIN_SRC_LEN) return "";
        return t.substring(0, Math.min(STEM_LEN, t.length()));
    }

    private static String baseToken(String s) {
        String x = safe(s).trim();
        if (x.isEmpty()) return "";

        String[] parts = x.split("\\s+");
        String best = parts[0];
        for (String p : parts) {
            String pp = p.trim();
            if (pp.length() > best.length()) best = pp;
        }
        return best;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
