package org.taniwha.service.enrichment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.model.ColumnRecord;
import org.taniwha.service.RDFService;

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
import org.springframework.beans.factory.annotation.Autowired;

// Calls OpenMed /infer_batch and returns validated lookup terms for later Snowstorm resolution.
@Service
public class OpenMedTerminologyService {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedTerminologyService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // Mirrors TerminologyTermInferenceService.InferredTerm.
    public record InferredTerm(String colKey, String colSearchTerm, Map<String, String> valueSearchTerms) {}

    @Autowired
    private ObjectProvider<ScaleInferenceService> scaleInferenceProvider;

    @Autowired
    private ObjectProvider<RDFService> rdfServiceProvider;

    @Value("${openmed.service.url:http://localhost:8002}")
    private String openmedUrl;

    @Value("${openmed.enabled:true}")
    private boolean enabled;

    @Value("${openmed.batch.size:5}")
    private int configuredBatchSize;

    @Value("${openmed.timeout.ms:10000}")
    private int timeoutMs;

    @Value("${snowstorm.enabled:true}")
    private boolean snowstormEnabled;

    public OpenMedTerminologyService(RDFService ignored) {
    }

    // Returns the configured batch size (min 1).
    public int batchSize() {
        return Math.max(1, configuredBatchSize);
    }

    public List<InferredTerm> inferBatch(List<ColumnRecord> columns) {
        if (!enabled || columns == null || columns.isEmpty()) {
            logger.debug("[OpenMed] inferBatch skipped (enabled={}, columns={})",
                    enabled, columns == null ? "null" : columns.size());
            return List.of();
        }

        logger.info("[OpenMed] inferBatch – {} column(s)", columns.size());

        List<OpenMedColumnTerms> openmedResult = callOpenMedService(columns);
        if (openmedResult == null || openmedResult.isEmpty()) {
            logger.warn("[OpenMed] No terms returned by OpenMed service; returning empty");
            return List.of();
        }

        List<InferredTerm> result = new ArrayList<>(openmedResult.size());
        for (OpenMedColumnTerms ct : openmedResult) {
            if (ct == null || ct.colKey == null || ct.colKey.isBlank()) continue;

            String colSearchTerm = safe(ct.colSearchTerm).trim();
            if (!isValidTerm(colSearchTerm)) {
                logger.debug("[OpenMed] col='{}': term '{}' invalid, falling back to colKey",
                        ct.colKey, colSearchTerm);
                colSearchTerm = ct.colKey;
            }

            // Drop invalid value terms so later Snowstorm lookups stay meaningful.
            Map<String, String> valueSearchTerms = new LinkedHashMap<>();
            if (ct.valueTerms != null) {
                for (Map.Entry<String, String> entry : ct.valueTerms.entrySet()) {
                    String rawVal = safe(entry.getKey()).trim();
                    String valTerm = safe(entry.getValue()).trim();
                    if (rawVal.isEmpty()) continue;
                    if (isValidTerm(valTerm)) {
                        String enrichedTerm = enrichValueTermWithScaleContext(rawVal, valTerm, ct.colKey, ct.valueTerms == null ? List.of() : List.copyOf(ct.valueTerms.keySet()));
                        valueSearchTerms.put(rawVal, enrichedTerm);
                    } else {
                        logger.debug("[OpenMed] col='{}' val='{}': term '{}' invalid, omitting",
                                ct.colKey, rawVal, valTerm);
                    }
                }
            }

            result.add(new InferredTerm(ct.colKey, colSearchTerm, valueSearchTerms));
            logger.debug("[OpenMed] col='{}' → searchTerm='{}' (values: {})",
                    ct.colKey, colSearchTerm, valueSearchTerms.size());
        }

        logger.info("[OpenMed] inferBatch done – {} search term(s) validated", result.size());
        return result;
    }

    @Deprecated(since = "1.0", forRemoval = false)
    String resolveWithSnowstorm(String openmedTerm, String fallbackText) {
        String term = safe(openmedTerm).trim();

        if (!isValidTerm(term)) {
            term = safe(fallbackText).trim();
            if (!isValidTerm(term)) {
                return "";
            }
        }

        if (!snowstormEnabled) {
            return "";
        }

        RDFService rdfService = rdfServiceProvider == null ? null : rdfServiceProvider.getIfAvailable();
        if (rdfService == null) {
            return "";
        }

        try {
            return firstSnowstormCode(rdfService.getSNOMEDTermSuggestions(term));
        } catch (Exception e) {
            logger.warn("[OpenMed] Snowstorm lookup failed for term='{}': {}", shortStr(term), e.toString());
            return "";
        }
    }

    // Let the LLM add short scale semantics when it can infer them from the value set.
    private String enrichValueTermWithScaleContext(String rawVal, String valueTerm, String columnKey, List<String> sampleValues) {
        ScaleInferenceService sis = (scaleInferenceProvider == null) ? null : scaleInferenceProvider.getIfAvailable();
        if (sis != null) {
            try {
                Map<String, String> mapping = sis.inferLabels(columnKey, sampleValues);
                if (mapping != null && mapping.containsKey(rawVal)) {
                    String semantic = mapping.get(rawVal);
                    if (semantic != null && !semantic.isBlank()) {
                        String result = valueTerm + " " + semantic.trim();
                        logger.debug("[OpenMed] LLM-enriched val='{}' in col='{}': '{}' -> '{}'", rawVal, columnKey, valueTerm, result);
                        return result;
                    }
                }
            } catch (Exception e) {
                logger.debug("[OpenMed] ScaleInferenceService unavailable: {}", e.getMessage());
            }
        }

        return valueTerm;
    }

    // Reject obvious artefacts before they reach Snowstorm.
    public boolean isValidTerm(String term) {
        if (term == null || term.isBlank()) return false;
        String t = term.trim();
        if (t.length() < 2) {
            logger.debug("[OpenMed] Rejected term '{}': too short (length={})", t, t.length());
            return false;
        }
        if (t.chars().allMatch(Character::isDigit)) {
            logger.debug("[OpenMed] Rejected term '{}': numeric-only", t);
            return false;
        }
        long alphaCount = t.chars().filter(Character::isLetter).count();
        if (alphaCount < 2) {
            logger.debug("[OpenMed] Rejected term '{}': fewer than 2 alphabetic chars (found {})", t, alphaCount);
            return false;
        }
        return true;
    }

    private List<OpenMedColumnTerms> callOpenMedService(List<ColumnRecord> columns) {
        try {
            List<Map<String, Object>> colInputs = new ArrayList<>(columns.size());
            for (ColumnRecord col : columns) {
                if (col == null) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("col_key", safe(col.colKey()).trim());
                c.put("values", col.values() != null ? col.values() : List.of());
                colInputs.add(c);
            }
            String body = OBJECT_MAPPER.writeValueAsString(Map.of("columns", colInputs));

            logger.debug("[OpenMed] POST {}/infer_batch – {} columns", openmedUrl, colInputs.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openmedUrl + "/infer_batch"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("[OpenMed] Non-200 response from OpenMed service: {}", response.statusCode());
                return List.of();
            }

            Map<String, Object> parsed = OBJECT_MAPPER.readValue(response.body(), new TypeReference<>() {});
            return parseOpenMedResponse(parsed);

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            logger.warn("[OpenMed] Could not connect to OpenMed service at {}: {}", openmedUrl, e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.warn("[OpenMed] Error calling OpenMed service: {}", e.toString());
            return List.of();
        }
    }

    private List<OpenMedColumnTerms> parseOpenMedResponse(Map<String, Object> parsed) {
        Object cols = parsed.get("columns");
        if (!(cols instanceof List<?> colList)) return List.of();

        List<OpenMedColumnTerms> out = new ArrayList<>();
        for (Object item : colList) {
            if (!(item instanceof Map<?, ?> m)) continue;
            OpenMedColumnTerms ct = new OpenMedColumnTerms();
            ct.colKey = toStr(m.get("col_key"));
            ct.colSearchTerm = toStr(m.get("col_search_term"));
            Object vt = m.get("value_terms");
            if (vt instanceof Map<?, ?> vtMap) {
                ct.valueTerms = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : vtMap.entrySet()) {
                    ct.valueTerms.put(toStr(e.getKey()), toStr(e.getValue()));
                }
            }
            out.add(ct);
        }
        return out;
    }

    private static final class OpenMedColumnTerms {
        String colKey;
        String colSearchTerm;
        Map<String, String> valueTerms;
    }

    private String firstSnowstormCode(List<OntologyTermDTO> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) return "";
        return suggestions.stream()
                .filter(s -> s != null && s.getIri() != null && !s.getIri().isBlank())
                .map(s -> {
                    String code = s.getIri().replaceAll(".*sct/", "").trim();
                    if (code.isEmpty()) return null;
                    String label = s.getLabel();
                    label = (label == null) ? "" : label.trim();
                    return label.isEmpty() ? code : (label + " | " + code);
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }

    private static String toStr(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String shortStr(String s) {
        String x = safe(s).trim();
        return x.length() <= 60 ? x : x.substring(0, 60) + "...";
    }
}
