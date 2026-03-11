package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Calls the OpenMed Python microservice's {@code POST /describe_batch} endpoint to
 * generate plain-language descriptions for dataset columns and their values.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Receives a list of {@link DescriptionService.ColumnEnrichmentInput} objects
 *       (already enriched with SNOMED terminology labels).</li>
 *   <li>Sends them to {@code /describe_batch} in the OpenMed service.</li>
 *   <li>Parses the response and returns a {@code Map<colKey, EnrichmentResult>} in the
 *       same shape that {@link DescriptionService} would produce.</li>
 * </ol>
 *
 * <p>Disabled by setting {@code openmed.enabled=false}.  When disabled or on any HTTP
 * error the method returns an empty map so {@link DescriptionService} can fall back to
 * the LLM.</p>
 */
@Service
public class OpenMedDescriptionService {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedDescriptionService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${openmed.service.url:http://localhost:8002}")
    private String openmedUrl;

    @Value("${openmed.enabled:true}")
    private boolean enabled;

    @Value("${openmed.timeout.ms:10000}")
    private int timeoutMs;

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Generates descriptions for the supplied columns via the OpenMed service.
     *
     * @param inputs columns to describe (terminology already resolved)
     * @return map from colKey to {@link DescriptionService.EnrichmentResult};
     *         empty when disabled or on error
     */
    public Map<String, DescriptionService.EnrichmentResult> describeColumns(
            List<DescriptionService.ColumnEnrichmentInput> inputs) {

        if (!enabled || inputs == null || inputs.isEmpty()) {
            logger.debug("[OpenMedDesc] describeColumns skipped (enabled={}, inputs={})",
                    enabled, inputs == null ? "null" : inputs.size());
            return Map.of();
        }

        logger.info("[OpenMedDesc] describeColumns – {} column(s)", inputs.size());

        try {
            // Build request payload
            List<Map<String, Object>> colPayload = new ArrayList<>(inputs.size());
            for (DescriptionService.ColumnEnrichmentInput in : inputs) {
                if (in == null) continue;
                String colKey = safe(in.colKey()).trim();
                if (colKey.isEmpty()) continue;

                Map<String, Object> c = new LinkedHashMap<>();
                c.put("col_key", colKey);
                c.put("terminology_label", extractLabel(safe(in.terminology())));

                List<Map<String, Object>> vals = new ArrayList<>();
                if (in.values() != null) {
                    for (DescriptionService.ValueSpec vs : in.values()) {
                        if (vs == null) continue;
                        String v = safe(vs.v()).trim();
                        if (v.isEmpty()) continue;
                        Map<String, Object> vm = new LinkedHashMap<>();
                        vm.put("v", v);
                        // Pass numeric range context so the Python service can generate
                        // scale-appropriate ordinal descriptions (e.g. Barthel 0-100).
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

            String body = objectMapper.writeValueAsString(Map.of("columns", colPayload));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openmedUrl + "/describe_batch"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

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

    // ------------------------------------------------------------------
    // Parse
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, DescriptionService.EnrichmentResult> parseDescribeResponse(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            Object colsObj = parsed.get("columns");
            if (!(colsObj instanceof List<?> cols)) return Map.of();

            Map<String, DescriptionService.EnrichmentResult> out = new LinkedHashMap<>();
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

                out.put(colKey, new DescriptionService.EnrichmentResult(colDesc, vmap));
                logger.debug("[OpenMedDesc] col='{}' → desc='{}' ({} values)", colKey, colDesc, vmap.size());
            }

            logger.info("[OpenMedDesc] parsed {} column description(s)", out.size());
            return out;

        } catch (Exception e) {
            logger.warn("[OpenMedDesc] Failed to parse /describe_batch response: {}", e.toString());
            return Map.of();
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Extracts the human-readable label from a SNOMED terminology string.
     * <p>Handles both formats:
     * <ul>
     *   <li>New: {@code "label | code"} – returns the part before {@code " | "}</li>
     *   <li>Legacy: {@code "code|label"} – returns the part after {@code "|"}</li>
     * </ul>
     */
    static String extractLabel(String terminology) {
        String t = safe(terminology).trim();
        if (t.isEmpty()) return "";
        // New format: "label | code"
        int sep = t.lastIndexOf(" | ");
        if (sep > 0) {
            String label = t.substring(0, sep).trim();
            if (!label.isEmpty()) return label;
        }
        // Legacy: "code|label"
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

    // DescriptionService.ColumnEnrichmentInput doesn't have a public terminology() accessor
    // in some versions; this helper works around that with the record accessor pattern.
    // The ColumnEnrichmentInput record fields are: colKey, terminology, values.
}
