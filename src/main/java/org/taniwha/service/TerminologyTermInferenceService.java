package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.model.ColumnRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

@Service
public class TerminologyTermInferenceService {

    private static final Logger logger = LoggerFactory.getLogger(TerminologyTermInferenceService.class);

    private final LLMTextGenerator llm;
    private final ExecutorService llmExecutor;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${terminology.infer.enabled:true}")
    private boolean enabled;

    @Value("${terminology.infer.batchSize:5}")
    private int batchSize;

    @Value("${terminology.infer.maxValuesPerColumn:10}")
    private int maxValuesPerColumn;

    @Value("${terminology.infer.maxChars:12000}")
    private int maxChars;

    @Value("${terminology.infer.maxRetries:2}")
    private int maxRetries;

    public record InferredTerm(String colKey, String colSearchTerm, Map<String, String> valueSearchTerms) {}

    // TerminologyTermInferenceService.java — add/replace these TWO helper functions
    private static String summarizeInferred(List<InferredTerm> inferred,
                                            int maxCols,
                                            int maxValsPerCol,
                                            int maxLen) {
        if (inferred == null || inferred.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(512);
        sb.append("[");
        int cols = 0;

        for (InferredTerm it : inferred) {
            if (it == null) continue;
            if (cols++ >= Math.max(1, maxCols)) break;

            String ck = shortStr(it.colKey(), maxLen);
            String ct = shortStr(it.colSearchTerm(), maxLen);

            sb.append("{colKey=").append(ck)
                    .append(", colSearchTerm=").append(ct);

            Map<String, String> vm = (it.valueSearchTerms() == null) ? Map.of() : it.valueSearchTerms();
            if (!vm.isEmpty()) {
                sb.append(", values=[");
                int v = 0;
                for (Map.Entry<String, String> e : vm.entrySet()) {
                    if (v++ >= Math.max(1, maxValsPerCol)) break;
                    sb.append("{raw=").append(shortStr(e.getKey(), maxLen))
                            .append(", term=").append(shortStr(e.getValue(), maxLen))
                            .append("},");
                }
                if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
                sb.append("]");
            }

            sb.append("},");
        }

        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        if (inferred.size() > cols) sb.append(", …");
        sb.append("]");
        return sb.toString();
    }

    private static String shortStr(String s, int maxLen) {
        String x = safe(s).trim().replaceAll("\\s+", " ");
        if (x.length() <= Math.max(1, maxLen)) return x;
        return x.substring(0, Math.max(1, maxLen)) + "...";
    }

    public TerminologyTermInferenceService(LLMTextGenerator llm,
                                           @Qualifier("llmExecutor") ExecutorService llmExecutor) {
        this.llm = llm;
        this.llmExecutor = llmExecutor;
    }

    public int batchSize() {
        return Math.max(1, batchSize);
    }

    // TerminologyTermInferenceService.java — replace this whole function
    public List<InferredTerm> infer(List<ColumnRecord> columns) {
        if (!enabled || llm == null || !llm.isEnabled() || columns == null || columns.isEmpty()) {
            return List.of();
        }

        String prompt = buildPrompt(columns);
        if (prompt.length() > maxChars) prompt = prompt.substring(0, maxChars);

        for (int attempt = 1; attempt <= Math.max(1, maxRetries); attempt++) {
            try {
                String raw = llm.generate(prompt);
                InferredBatch parsed = parse(raw);
                if (parsed == null || parsed.columns == null || parsed.columns.isEmpty()) {
                    logger.warn("[TermInfer] empty/invalid JSON (attempt {}/{})", attempt, maxRetries);
                    continue;
                }

                List<InferredTerm> out = new ArrayList<>();
                for (InferredColumn c : parsed.columns) {
                    if (c == null) continue;
                    String colKey = safe(c.colKey).trim();
                    if (colKey.isEmpty()) continue;

                    String colTerm = safe(c.colSearchTerm).trim();
                    Map<String, String> vmap = new LinkedHashMap<>();
                    if (c.values != null) {
                        for (InferredValue v : c.values) {
                            if (v == null) continue;
                            String rv = safe(v.raw).trim();
                            String st = safe(v.searchTerm).trim();
                            if (rv.isEmpty() || st.isEmpty()) continue;
                            vmap.put(rv, st);
                        }
                    }

                    out.add(new InferredTerm(colKey, colTerm, vmap));
                }

                logger.info("[TermInfer] inferred terms for {} columns", out.size());
                if (logger.isInfoEnabled()) {
                    logger.info("[TermInfer] {}", summarizeInferred(out, 5, 3, 80));
                }
                return out;

            } catch (Exception e) {
                logger.warn("[TermInfer] failed (attempt {}/{}): {}", attempt, maxRetries, e.toString());
            }
        }

        return List.of();
    }

    public List<InferredTerm> inferBatch(List<ColumnRecord> batch) {
        return infer(batch);
    }

    private String buildPrompt(List<ColumnRecord> cols) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (ColumnRecord c : cols) {
            if (c == null) continue;
            String colKey = safe(c.colKey()).trim();
            if (colKey.isEmpty()) continue;

            List<String> vals = new ArrayList<>();
            if (c.values() != null) {
                Set<String> seen = new LinkedHashSet<>();
                for (String v : c.values()) {
                    if (vals.size() >= Math.max(1, maxValuesPerColumn)) break;
                    String sv = safe(v).trim();
                    if (sv.isEmpty()) continue;
                    if (!seen.add(sv)) continue;
                    vals.add(sv);
                }
            }

            Map<String, Object> o = new LinkedHashMap<>();
            o.put("colKey", colKey);
            o.put("values", vals);
            payload.add(o);
        }

        String json;
        try {
            json = om.writeValueAsString(payload);
        } catch (Exception e) {
            json = "[]";
        }

        return ""
                + "Return ONLY minified JSON. No markdown. No extra keys.\n"
                + "You are given columns and all possible values each can have. For each column:\n"
                + "- Propose a SNOMED-searchable phrase for the COLUMN meaning (colSearchTerm).\n"
                + "- For each of it related values, propose a SNOMED-searchable phrase (searchTerm).\n"
                + "Output schema:\n"
                + "{\"columns\":[{\"colKey\":\"...\",\"colSearchTerm\":\"...\",\"values\":[{\"raw\":\"...\",\"searchTerm\":\"...\"}]}]}\n"
                + "Input:\n"
                + json;
    }

    private InferredBatch parse(String raw) {
        String s = safe(raw).trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) return null;
        s = s.substring(start, end + 1).trim();

        try {
            Map<String, Object> m = om.readValue(s, new TypeReference<>() {});
            Object cols = m.get("columns");
            if (!(cols instanceof List<?> list)) return null;

            List<InferredColumn> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> mm)) continue;

                InferredColumn ic = new InferredColumn();
                ic.colKey = toStr(mm.get("colKey"));
                ic.colSearchTerm = toStr(mm.get("colSearchTerm"));

                Object vals = mm.get("values");
                if (vals instanceof List<?> vl) {
                    ic.values = new ArrayList<>();
                    for (Object vv : vl) {
                        if (!(vv instanceof Map<?, ?> vm)) continue;
                        InferredValue iv = new InferredValue();
                        iv.raw = toStr(vm.get("raw"));
                        iv.searchTerm = toStr(vm.get("searchTerm"));
                        ic.values.add(iv);
                    }
                }

                out.add(ic);
            }

            InferredBatch b = new InferredBatch();
            b.columns = out;
            return b;

        } catch (Exception e) {
            return null;
        }
    }

    private static final class InferredBatch {
        List<InferredColumn> columns;
    }

    private static final class InferredColumn {
        String colKey;
        String colSearchTerm;
        List<InferredValue> values;
    }

    private static final class InferredValue {
        String raw;
        String searchTerm;
    }

    private static String toStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
