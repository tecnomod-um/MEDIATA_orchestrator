package org.taniwha.service.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.model.ColumnEnrichmentInput;
import org.taniwha.model.EnrichmentResult;
import org.taniwha.model.ValueSpec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Calls OpenMed /describe_batch and maps the response into EnrichmentResult objects.
@Service
public class OpenMedDescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedDescriptionService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${openmed.service.url:http://localhost:8002}")
    private String openmedUrl;

    @Value("${openmed.enabled:true}")
    private boolean enabled;

    @Value("${openmed.timeout.ms:10000}")
    private int timeoutMs;

    public Map<String, EnrichmentResult> describeColumns(
            List<ColumnEnrichmentInput> inputs) {

        if (!enabled || inputs == null || inputs.isEmpty()) {
            logger.debug("[OpenMedDesc] describeColumns skipped (enabled={}, inputs={})",
                    enabled, inputs == null ? "null" : inputs.size());
            return Map.of();
        }

        logger.info("[OpenMedDesc] describeColumns – {} column(s)", inputs.size());

        try {
            List<Map<String, Object>> colPayload = new ArrayList<>(inputs.size());
            for (ColumnEnrichmentInput in : inputs) {
                if (in == null) continue;
                String colKey = safe(in.colKey()).trim();
                if (colKey.isEmpty()) continue;

                Map<String, Object> c = new LinkedHashMap<>();
                c.put("col_key", colKey);
                c.put("terminology_label", extractLabel(safe(in.terminology())));

                List<Map<String, Object>> vals = new ArrayList<>();
                if (in.values() != null) {
                    for (ValueSpec vs : in.values()) {
                        if (vs == null) continue;
                        String v = safe(vs.v()).trim();
                        if (v.isEmpty()) continue;
                        Map<String, Object> vm = new LinkedHashMap<>();
                        vm.put("v", v);
                        // Keep numeric range hints so OpenMed can describe scale values sensibly.
                        String min = safe(vs.min()).trim();
                        String max = safe(vs.max()).trim();
                        if (!min.isEmpty()) vm.put("min", min);
                        if (!max.isEmpty()) vm.put("max", max);
                        vals.add(vm);
                    }
                }
                c.put("values", vals);
                colPayload.add(c);
            }

            String body = OBJECT_MAPPER.writeValueAsString(Map.of("columns", colPayload));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openmedUrl + "/describe_batch"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("[OpenMedDesc] Non-200 from OpenMed /describe_batch: {}", response.statusCode());
                return Map.of();
            }

            return parseDescribeResponse(response.body());

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            logger.warn("[OpenMedDesc] Could not connect to OpenMed service at {}: {}", openmedUrl, e.getMessage());
            return Map.of();
        } catch (Exception e) {
            logger.warn("[OpenMedDesc] Error calling /describe_batch: {}", e.toString());
            return Map.of();
        }
    }

    private Map<String, EnrichmentResult> parseDescribeResponse(String json) {
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, new TypeReference<>() {});
            Object colsObj = parsed.get("columns");
            if (!(colsObj instanceof List<?> cols)) return Map.of();

            Map<String, EnrichmentResult> out = new LinkedHashMap<>();
            for (Object item : cols) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String colKey = toStr(m.get("col_key")).trim();
                if (colKey.isEmpty()) continue;
                String colDesc = toStr(m.get("col_desc")).trim();
                if (colDesc.isEmpty()) colDesc = colKey;

                Map<String, String> vmap = new LinkedHashMap<>();
                Object valsObj = m.get("values");
                if (valsObj instanceof List<?> vals) {
                    for (Object vi : vals) {
                        if (!(vi instanceof Map<?, ?> vm)) continue;
                        String v = toStr(vm.get("v")).trim();
                        String d = toStr(vm.get("d")).trim();
                        if (!v.isEmpty()) vmap.put(v, d.isEmpty() ? v : d);
                    }
                }

                out.put(colKey, new EnrichmentResult(colDesc, vmap));
                logger.debug("[OpenMedDesc] col='{}' → desc='{}' ({} values)", colKey, colDesc, vmap.size());
            }

            logger.info("[OpenMedDesc] parsed {} column description(s)", out.size());
            return out;

        } catch (Exception e) {
            logger.warn("[OpenMedDesc] Failed to parse /describe_batch response: {}", e.toString());
            return Map.of();
        }
    }

    // Accept both "label | code" and the older "code|label" format.
    static String extractLabel(String terminology) {
        String t = safe(terminology).trim();
        if (t.isEmpty()) return "";
        int sep = t.lastIndexOf(" | ");
        if (sep > 0) {
            String label = t.substring(0, sep).trim();
            if (!label.isEmpty()) return label;
        }
        int bar = t.indexOf('|');
        if (bar >= 0 && bar < t.length() - 1) {
            String right = t.substring(bar + 1).trim();
            if (!right.isEmpty()) return right;
        }
        return t;
    }

    private static String toStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

}
