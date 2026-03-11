package org.taniwha.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.model.ColumnRecord;

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
 * Infers SNOMED-searchable terminology for dataset columns and their values
 * using the OpenMed Python microservice, then resolves each inferred term
 * against Snowstorm (SNOMED CT).
 *
 * <h3>Pipeline</h3>
 * <ol>
 *   <li>Send batches of {@link ColumnRecord} objects to the OpenMed service
 *       ({@code POST /infer_batch}) – the service runs a biomedical NER model
 *       (d4data/biomedical-ner-all) to suggest a SNOMED-searchable phrase for
 *       every column header and each of its distinct values.</li>
 *   <li>Validate every suggested phrase with {@link #isValidTerm(String)}: reject
 *       numeric-only tokens, single-character artefacts, etc.; fall back to the
 *       column name when the column-level suggestion is invalid.</li>
 *   <li>Return validated search phrases as {@link InferredTerm} objects.
 *       <strong>Snowstorm lookup is intentionally not performed here</strong> –
 *       it is delegated to {@code TerminologyLookupService} in
 *       {@code MappingEnrichmentHelper}, which performs a single, deduped,
 *       bulk lookup for the whole mapping batch.</li>
 * </ol>
 *
 * <p>The helper method {@link #resolveWithSnowstorm(String, String)} is also
 * available for direct Snowstorm lookups, e.g. in integration tests.
 * When Snowstorm returns no match it returns {@code ""}, so callers can be
 * sure the return value is either a valid SNOMED code+label or empty.</p>
 *
 * <p>Disable this service by setting {@code openmed.enabled=false}.</p>
 */
@Service
public class OpenMedTerminologyService {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedTerminologyService.class);

    /** Mirrors the structure of {@link TerminologyTermInferenceService.InferredTerm}. */
    public record InferredTerm(String colKey, String colSearchTerm, Map<String, String> valueSearchTerms) {}

    private final RDFService rdfService;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    public OpenMedTerminologyService(RDFService rdfService) {
        this.rdfService = rdfService;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Returns the configured batch size (min 1). */
    public int batchSize() {
        return Math.max(1, configuredBatchSize);
    }

    /**
     * Infers and validates search terms for one batch of columns.
     *
     * <p>Calls the OpenMed Python service, validates each returned phrase with
     * {@link #isValidTerm(String)}, then returns one {@link InferredTerm} per
     * column.  Invalid column-level terms (e.g. bare numbers) fall back to the
     * column name; invalid value-level terms are omitted so no Snowstorm query
     * is wasted on them.
     *
     * <p><strong>Snowstorm is not queried here.</strong>  The caller
     * ({@code MappingEnrichmentHelper}) performs a single deduped bulk lookup
     * via {@code TerminologyLookupService} after collecting all search terms.
     *
     * @param columns a batch of column records (not null)
     * @return validated search terms; empty list when disabled or on error
     */
    public List<InferredTerm> inferBatch(List<ColumnRecord> columns) {
        if (!enabled || columns == null || columns.isEmpty()) {
            logger.debug("[OpenMed] inferBatch skipped (enabled={}, columns={})",
                    enabled, columns == null ? "null" : columns.size());
            return List.of();
        }

        logger.info("[OpenMed] inferBatch – {} column(s)", columns.size());

        // --- Call the OpenMed service ---
        List<OpenMedColumnTerms> openmedResult = callOpenMedService(columns);
        if (openmedResult == null || openmedResult.isEmpty()) {
            logger.warn("[OpenMed] No terms returned by OpenMed service; returning empty");
            return List.of();
        }

        // --- Validate and package as InferredTerm (no Snowstorm lookup here) ---
        List<InferredTerm> result = new ArrayList<>(openmedResult.size());
        for (OpenMedColumnTerms ct : openmedResult) {
            if (ct == null || ct.colKey == null || ct.colKey.isBlank()) continue;

            // Column-level: if OpenMed's suggestion is invalid, use the column name itself.
            String colSearchTerm = safe(ct.colSearchTerm).trim();
            if (!isValidTerm(colSearchTerm)) {
                logger.debug("[OpenMed] col='{}': term '{}' invalid, falling back to colKey",
                        ct.colKey, colSearchTerm);
                colSearchTerm = ct.colKey;
            }

            // Value-level: only include values whose suggested term is valid.
            // Numeric or artefact values (e.g. "40", "a") are omitted so Snowstorm
            // is not queried with meaningless tokens.
            Map<String, String> valueSearchTerms = new LinkedHashMap<>();
            if (ct.valueTerms != null) {
                for (Map.Entry<String, String> entry : ct.valueTerms.entrySet()) {
                    String rawVal = safe(entry.getKey()).trim();
                    String valTerm = safe(entry.getValue()).trim();
                    if (rawVal.isEmpty()) continue;
                    if (isValidTerm(valTerm)) {
                        valueSearchTerms.put(rawVal, valTerm);
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

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Resolves an OpenMed-suggested phrase against Snowstorm.
     *
     * <p>This is a utility helper available for direct calls (e.g. integration tests).
     * {@link #inferBatch} no longer calls it; the bulk Snowstorm resolution is done
     * by {@code TerminologyLookupService} inside {@code MappingEnrichmentHelper}.
     *
     * @param openmedTerm the phrase suggested by OpenMed (may be empty / invalid)
     * @param fallbackText text to try when {@code openmedTerm} fails {@link #isValidTerm}
     * @return a SNOMED code+label string (e.g. {@code "73211009|Diabetes mellitus"})
     *         when Snowstorm finds a match, or {@code ""} when it does not –
     *         the raw OpenMed phrase is <em>never</em> returned
     */
    String resolveWithSnowstorm(String openmedTerm, String fallbackText) {
        String term = safe(openmedTerm).trim();

        // Validate the OpenMed term; if it fails, try the fallback text.
        if (!isValidTerm(term)) {
            if (!term.isEmpty()) {
                logger.debug("[OpenMed] Term '{}' failed validation; trying fallback text", shortStr(term));
            }
            term = safe(fallbackText).trim();
            if (!isValidTerm(term)) {
                logger.debug("[OpenMed] Fallback text '{}' also failed validation; returning empty", shortStr(term));
                return "";
            }
        }

        if (!snowstormEnabled) {
            logger.debug("[OpenMed] Snowstorm disabled – cannot resolve '{}' to SNOMED; returning empty", term);
            return "";
        }

        List<OntologyTermDTO> suggestions = null;
        try {
            suggestions = rdfService.getSNOMEDTermSuggestions(term);
        } catch (Exception e) {
            logger.warn("[OpenMed] Snowstorm lookup failed for term='{}': {}", shortStr(term), e.toString());
        }

        String code = firstSnowstormCode(suggestions);
        if (!code.isEmpty()) {
            logger.debug("[OpenMed] Snowstorm resolved '{}' → '{}'", shortStr(term), shortStr(code));
            return code;
        }

        // Snowstorm returned nothing – the terminology field must remain empty.
        // The raw search phrase is NOT stored as a terminology value.
        logger.debug("[OpenMed] Snowstorm returned nothing for '{}'; returning empty (SNOMED required)", shortStr(term));
        return "";
    }

    /**
     * Returns {@code true} when {@code term} is a plausible medical search phrase.
     *
     * <p>Rejects terms that are:
     * <ul>
     *   <li>Empty or blank</li>
     *   <li>Composed entirely of digits (e.g. raw numeric values like {@code "42"})</li>
     *   <li>Shorter than 2 characters</li>
     *   <li>Lacking at least 2 alphabetic characters (e.g. punctuation-only tokens)</li>
     * </ul>
     * These cases indicate that OpenMed received non-medical input (IDs, counts,
     * measurements) and the caller should fall back to the original column name or
     * a broader context term.
     *
     * @param term candidate search phrase
     * @return {@code true} if the term is worth querying in Snowstorm
     */
    public boolean isValidTerm(String term) {
        if (term == null || term.isBlank()) return false;
        String t = term.trim();
        if (t.length() < 2) {
            logger.debug("[OpenMed] Rejected term '{}': too short (length={})", t, t.length());
            return false;
        }
        // Reject purely numeric terms (e.g. "40", "123")
        if (t.chars().allMatch(Character::isDigit)) {
            logger.debug("[OpenMed] Rejected term '{}': numeric-only", t);
            return false;
        }
        // Require at least 2 alphabetic characters to avoid punctuation artefacts
        long alphaCount = t.chars().filter(Character::isLetter).count();
        if (alphaCount < 2) {
            logger.debug("[OpenMed] Rejected term '{}': fewer than 2 alphabetic chars (found {})", t, alphaCount);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // HTTP call to the OpenMed Python service
    // ------------------------------------------------------------------

    private List<OpenMedColumnTerms> callOpenMedService(List<ColumnRecord> columns) {
        try {
            // Build JSON request body
            List<Map<String, Object>> colInputs = new ArrayList<>(columns.size());
            for (ColumnRecord col : columns) {
                if (col == null) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("col_key", safe(col.colKey()).trim());
                c.put("values", col.values() != null ? col.values() : List.of());
                colInputs.add(c);
            }
            String body = objectMapper.writeValueAsString(Map.of("columns", colInputs));

            logger.debug("[OpenMed] POST {}/infer_batch – {} columns", openmedUrl, colInputs.size());

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .version(HttpClient.Version.HTTP_1_1)   // uvicorn speaks HTTP/1.1
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openmedUrl + "/infer_batch"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("[OpenMed] Non-200 response from OpenMed service: {}", response.statusCode());
                return List.of();
            }

            Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {});
            return parseOpenMedResponse(parsed);

        } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
            logger.warn("[OpenMed] Could not connect to OpenMed service at {}: {}", openmedUrl, e.getMessage());
            return List.of();
        } catch (Exception e) {
            logger.warn("[OpenMed] Error calling OpenMed service: {}", e.toString());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
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

    // ------------------------------------------------------------------
    // Snowstorm helpers
    // ------------------------------------------------------------------

    private String firstSnowstormCode(List<OntologyTermDTO> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) return "";
        return suggestions.stream()
                .filter(s -> s != null && s.getIri() != null && !s.getIri().isBlank())
                .map(s -> {
                    String code = s.getIri().replaceAll(".*sct/", "").trim();
                    if (code.isEmpty()) return null;
                    String label = s.getLabel();
                    label = (label == null) ? "" : label.trim();
                    return label.isEmpty() ? code : (code + "|" + label);
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }

    // ------------------------------------------------------------------
    // Internal data holders
    // ------------------------------------------------------------------

    private static final class OpenMedColumnTerms {
        String colKey;
        String colSearchTerm;
        Map<String, String> valueTerms;
    }

    // ------------------------------------------------------------------
    // Utility
    // ------------------------------------------------------------------

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
