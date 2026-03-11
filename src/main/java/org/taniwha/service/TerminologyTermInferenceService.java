package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.model.ColumnRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Infers SNOMED-searchable search terms for columns and their values using the
 * standalone <strong>OpenMed</strong> NER service.
 *
 * <p>OpenMed is a specialised Python library backed by HuggingFace biomedical
 * NER models ({@code OpenMed/OpenMed-NER-*}).  The companion service
 * ({@code openmed-service/main.py}) exposes a single
 * {@code POST /infer-terms} endpoint that accepts a batch of column names +
 * sampled values and returns the best medical entity text for each — which is
 * then used as a Snowstorm search term.</p>
 *
 * <p>If the OpenMed service is disabled or unreachable this class returns an
 * empty list and logs the failure explicitly.  It does <em>not</em> fall back
 * to the generic LLM.</p>
 */
@Service
public class TerminologyTermInferenceService {

    private static final Logger logger = LoggerFactory.getLogger(TerminologyTermInferenceService.class);

    private final OpenMedInferenceService openMedService;

    @Value("${terminology.infer.enabled:true}")
    private boolean enabled;

    @Value("${terminology.infer.batchSize:5}")
    private int batchSize;

    /**
     * DTO returned by {@link #infer(List)}.
     */
    public record InferredTerm(String colKey, String colSearchTerm, Map<String, String> valueSearchTerms) {}

    public TerminologyTermInferenceService(ObjectProvider<OpenMedInferenceService> openMedProvider) {
        this.openMedService = openMedProvider.getIfAvailable();
        if (this.openMedService == null) {
            logger.warn("[TermInfer] OpenMedInferenceService bean not available - "
                    + "terminology inference will be skipped.");
        }
    }

    public int batchSize() {
        return Math.max(1, batchSize);
    }

    /**
     * Infers medical search terms for a batch of columns via the OpenMed service.
     *
     * <p>If the service is unavailable, an empty list is returned and the reason
     * is logged at ERROR level — the pipeline will then fall back to using the
     * raw column key as its Snowstorm search term.</p>
     */
    public List<InferredTerm> infer(List<ColumnRecord> columns) {
        if (!enabled) {
            logger.debug("[TermInfer] Terminology inference disabled.");
            return List.of();
        }
        if (openMedService == null || !openMedService.isEnabled()) {
            logger.warn("[TermInfer] OpenMed service is disabled - terminology inference skipped.");
            return List.of();
        }
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }

        if (!openMedService.isAvailable()) {
            logger.error("[TermInfer] OpenMed service is not available at the configured URL. "
                    + "Terminology inference will be skipped for this batch. "
                    + "Start the service with:  uvicorn main:app  (see openmed-service/main.py)");
            return List.of();
        }

        List<OpenMedInferenceService.InferredTermResult> results =
                openMedService.inferBatch(columns);

        List<InferredTerm> out = new ArrayList<>();
        for (OpenMedInferenceService.InferredTermResult r : results) {
            if (r == null) continue;
            String colKey = safe(r.colKey()).trim();
            if (colKey.isEmpty()) continue;
            String colSearchTerm = safe(r.colSearchTerm()).trim();
            if (colSearchTerm.isEmpty()) colSearchTerm = colKey;

            Map<String, String> vmap = r.valueSearchTerms() != null
                    ? new LinkedHashMap<>(r.valueSearchTerms())
                    : new LinkedHashMap<>();
            out.add(new InferredTerm(colKey, colSearchTerm, vmap));
        }

        logger.info("[TermInfer] inferred terms for {} columns via OpenMed", out.size());
        if (logger.isInfoEnabled()) {
            logger.info("[TermInfer] {}", summarize(out, 5, 3, 80));
        }
        return out;
    }

    /** Convenience alias kept for callers that already use {@code inferBatch}. */
    public List<InferredTerm> inferBatch(List<ColumnRecord> batch) {
        return infer(batch);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String summarize(List<InferredTerm> inferred, int maxCols,
                                    int maxValsPerCol, int maxLen) {
        if (inferred == null || inferred.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder(512);
        sb.append("[");
        int cols = 0;
        for (InferredTerm it : inferred) {
            if (it == null) continue;
            if (cols++ >= Math.max(1, maxCols)) break;
            sb.append("{colKey=").append(shortStr(it.colKey(), maxLen))
              .append(", colSearchTerm=").append(shortStr(it.colSearchTerm(), maxLen));
            Map<String, String> vm = it.valueSearchTerms() == null ? Map.of() : it.valueSearchTerms();
            if (!vm.isEmpty()) {
                sb.append(", values=[");
                int v = 0;
                for (Map.Entry<String, String> e : vm.entrySet()) {
                    if (v++ >= Math.max(1, maxValsPerCol)) break;
                    sb.append("{raw=").append(shortStr(e.getKey(), maxLen))
                      .append(", term=").append(shortStr(e.getValue(), maxLen)).append("},");
                }
                if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
                sb.append("]");
            }
            sb.append("},");
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        if (inferred.size() > cols) sb.append(", ...");
        sb.append("]");
        return sb.toString();
    }

    private static String shortStr(String s, int maxLen) {
        String x = safe(s).trim().replaceAll("\\s+", " ");
        return x.length() <= Math.max(1, maxLen) ? x : x.substring(0, Math.max(1, maxLen)) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
