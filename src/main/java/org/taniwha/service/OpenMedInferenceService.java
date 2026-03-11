package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.model.ColumnRecord;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP client for the standalone OpenMed NER inference service.
 *
 * <p>The companion Python service ({@code openmed-service/main.py}) wraps the
 * {@code openmed} library and exposes a single {@code POST /infer-terms} endpoint
 * that accepts column names + sampled values and returns the best medical search
 * term for each, extracted by a domain-specific HuggingFace NER model.</p>
 *
 * <p>If the service is disabled or unreachable this class returns an empty result
 * and logs the failure clearly — it does <em>not</em> fall back to the generic LLM.</p>
 */
@Service
public class OpenMedInferenceService {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedInferenceService.class);

    private final ObjectMapper om = new ObjectMapper();

    @Value("${openmed.service.url:http://localhost:8002}")
    private String serviceUrl;

    @Value("${openmed.service.enabled:true}")
    private boolean enabled;

    @Value("${openmed.service.timeoutMs:15000}")
    private int timeoutMs;

    @Value("${terminology.infer.maxValuesPerColumn:10}")
    private int maxValuesPerColumn;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns {@code true} when the service is enabled AND its {@code /health}
     * endpoint responds with HTTP 200.
     */
    public boolean isAvailable() {
        if (!enabled) return false;
        try {
            HttpURLConnection conn = openConnection(serviceUrl + "/health", "GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Calls {@code POST /infer-terms} on the OpenMed service with one batch of columns.
     *
     * @param columns columns (name + sampled values) to infer terminology for
     * @return inferred terms, or an empty list when the service is unavailable
     */
    public List<InferredTermResult> inferBatch(List<ColumnRecord> columns) {
        if (!enabled) {
            logger.warn("[OpenMedService] Service is disabled (openmed.service.enabled=false). "
                    + "Terminology inference will be skipped.");
            return List.of();
        }
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }

        String requestBody = buildRequestBody(columns);
        if (requestBody == null) return List.of();

        String responseBody;
        try {
            responseBody = postJson(serviceUrl + "/infer-terms", requestBody);
        } catch (Exception e) {
            logger.error("[OpenMedService] OpenMed service is unavailable at {}. "
                    + "Terminology inference will be skipped for this batch. Cause: {}",
                    serviceUrl, e.toString());
            return List.of();
        }

        return parseResponse(responseBody);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private String buildRequestBody(List<ColumnRecord> columns) {
        try {
            List<Map<String, Object>> cols = new ArrayList<>();
            for (ColumnRecord c : columns) {
                if (c == null) continue;
                String colKey = safe(c.colKey()).trim();
                if (colKey.isEmpty()) continue;

                Set<String> seen = new LinkedHashSet<>();
                List<String> vals = new ArrayList<>();
                if (c.values() != null) {
                    for (String v : c.values()) {
                        if (vals.size() >= Math.max(1, maxValuesPerColumn)) break;
                        String sv = safe(v).trim();
                        if (sv.isEmpty() || !seen.add(sv)) continue;
                        vals.add(sv);
                    }
                }

                Map<String, Object> col = new LinkedHashMap<>();
                col.put("colKey", colKey);
                col.put("values", vals);
                cols.add(col);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("columns", cols);
            return om.writeValueAsString(body);
        } catch (Exception e) {
            logger.warn("[OpenMedService] Failed to build request body: {}", e.toString());
            return null;
        }
    }

    private List<InferredTermResult> parseResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return List.of();
        try {
            Map<String, Object> root = om.readValue(responseBody, new TypeReference<>() {});
            Object colsObj = root.get("columns");
            if (!(colsObj instanceof List<?> colsList)) return List.of();

            List<InferredTermResult> out = new ArrayList<>();
            for (Object o : colsList) {
                if (!(o instanceof Map<?, ?> colMap)) continue;
                String colKey = toStr(colMap.get("colKey")).trim();
                String colSearchTerm = toStr(colMap.get("colSearchTerm")).trim();
                if (colKey.isEmpty()) continue;
                if (colSearchTerm.isEmpty()) colSearchTerm = colKey;

                Map<String, String> vmap = new LinkedHashMap<>();
                Object valsObj = colMap.get("values");
                if (valsObj instanceof List<?> valsList) {
                    for (Object vv : valsList) {
                        if (!(vv instanceof Map<?, ?> vm)) continue;
                        String raw = toStr(vm.get("raw")).trim();
                        String term = toStr(vm.get("searchTerm")).trim();
                        if (!raw.isEmpty() && !term.isEmpty()) vmap.put(raw, term);
                    }
                }
                out.add(new InferredTermResult(colKey, colSearchTerm, vmap));
            }

            logger.info("[OpenMedService] Received inferred terms for {} columns", out.size());
            return out;
        } catch (Exception e) {
            logger.warn("[OpenMedService] Failed to parse response: {}", e.toString());
            return List.of();
        }
    }

    private String postJson(String urlStr, String body) throws Exception {
        HttpURLConnection conn = openConnection(urlStr, "POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        try (var out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("HTTP " + code + " from OpenMed service at " + urlStr);
        }
        try (var in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static HttpURLConnection openConnection(String urlStr, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        return conn;
    }

    private static String toStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------
    // Result record
    // ------------------------------------------------------------------

    public record InferredTermResult(
            String colKey,
            String colSearchTerm,
            Map<String, String> valueSearchTerms) {
    }
}
