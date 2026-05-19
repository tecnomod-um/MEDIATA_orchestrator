package org.taniwha.service.enrichment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.service.EmbeddingsClient;
import org.taniwha.service.RDFService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class TerminologyLookupService {

    private static final Logger logger = LoggerFactory.getLogger(TerminologyLookupService.class);

    private final RDFService rdfService;
    private final EmbeddingsClient embeddingsClient;
    private ObjectProvider<ParaphraseService> paraphraseProvider;

    private final ConcurrentMap<String, String> terminologyCache = new ConcurrentHashMap<>();

    private final ExecutorService executorService;
    private final boolean ownsExecutor;

    @Value("${snowstorm.enabled:true}")
    private boolean snowstormEnabled;

    @Value("${snowstorm.timeout:30}")
    private int snowstormTimeoutSeconds;

    @Value("${snowstorm.lookup.retries:1}")
    private int snowstormLookupRetries;

    @Value("${snowstorm.lookup.retryBackoffMs:150}")
    private long snowstormLookupRetryBackoffMs;

    // Disabled by default because synthetic fallback codes are rejected downstream anyway.
    @Value("${terminology.fallback.enabled:false}")
    private boolean fallbackEnabled;

    @Autowired
    public TerminologyLookupService(RDFService rdfService,
                                    EmbeddingsClient embeddingsClient,
                                    @Qualifier("llmExecutor") ObjectProvider<ExecutorService> executorProvider,
                                    ObjectProvider<ParaphraseService> paraphraseProvider) {
        this.rdfService = rdfService;
        this.embeddingsClient = embeddingsClient;

        ExecutorService injected = executorProvider.getIfAvailable();
        if (injected != null) {
            this.executorService = injected;
            this.ownsExecutor = false;
            logger.info("[TerminologyService] Using injected executor (shared)");
        } else {
            this.executorService = Executors.newFixedThreadPool(10);
            this.ownsExecutor = true;
            logger.info("[TerminologyService] Created internal executor (size=10)");
        }
        this.paraphraseProvider = paraphraseProvider;
    }

    public static class TerminologyRequest {
        public final String term;
        public final String context;

        public TerminologyRequest(String term, String context) {
            this.term = term;
            this.context = context;
        }
    }

    // Non-blocking counterpart to batchLookupTerminology.
    public CompletableFuture<Map<String, String>> submitLookupAsync(List<TerminologyRequest> requests) {
        if (!snowstormEnabled || requests == null || requests.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        LookupBatch batch = executeLookupBatch(requests, "async lookup error");
        if (batch.futures.isEmpty()) {
            return CompletableFuture.completedFuture(batch.results);
        }

        return CompletableFuture.allOf(batch.futures.toArray(new CompletableFuture[0]))
                .orTimeout(snowstormTimeoutSeconds, TimeUnit.SECONDS)
                .handle((v, ex) -> {
                    if (ex != null) {
                        logger.warn("[TerminologyService] async batch timed out or error: {}", ex.toString());
                    }
                    return batch.results;
                });
    }

    public Map<String, String> batchLookupTerminology(List<TerminologyRequest> requests) {
        if (!snowstormEnabled || requests == null || requests.isEmpty()) return Collections.emptyMap();

        LookupBatch batch = executeLookupBatch(requests, "lookup error");

        try {
            CompletableFuture.allOf(batch.futures.toArray(new CompletableFuture[0]))
                    .get(snowstormTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("[TerminologyService] batch timeout after {}s", snowstormTimeoutSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[TerminologyService] batch interrupted: {}", e.toString());
        } catch (Exception e) {
            logger.warn("[TerminologyService] batch error: {}", e.toString());
        }

        return batch.results;
    }

    public String selectBestTerminology(String term, String context) {
        String t = safe(term).trim();
        if (t.isEmpty()) return "";
        if (!snowstormEnabled) return "";

        String ctx = safe(context).trim();
        String cacheKey = t + "|" + ctx;

        String cached = terminologyCache.get(cacheKey);
        if (cached != null) return cached;

        String out;
        try {
            LookupParts lp = splitValueKey(t, ctx);
            out = lookupSingleTerminology(lp.lookupTerm, lp.lookupContext);
        } catch (Exception e) {
            logger.warn("[TerminologyService] select error term='{}': {}", t, e.toString());
            out = "";
        }

        if (out == null) out = "";
        if (!out.isBlank()) {
            terminologyCache.put(cacheKey, out);
        }
        return out;
    }

    public void shutdown() {
        if (!ownsExecutor) return;
        executorService.shutdown();
    }

    public void clearCache() {
        terminologyCache.clear();
    }

    private LookupParts splitValueKey(String termKey, String context) {
        String t = safe(termKey).trim();
        String ctx = safe(context).trim();

        int bar = t.indexOf('|');
        if (bar >= 0 && bar < t.length() - 1) {
            String left = t.substring(0, bar).trim();
            String right = t.substring(bar + 1).trim();
            if (!right.isEmpty()) {
                String lc = ctx.isEmpty() ? left : ctx;
                return new LookupParts(right, lc);
            }
        }
        return new LookupParts(t, ctx);
    }

    private LookupBatch executeLookupBatch(List<TerminologyRequest> requests, String logPrefix) {
        Map<String, String> results = new ConcurrentHashMap<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (TerminologyRequest request : requests) {
            if (request == null) continue;

            final String termKey = safe(request.term).trim();
            if (termKey.isEmpty()) continue;

            final String ctx = safe(request.context).trim();
            final String cacheKey = termKey + "|" + ctx;

            String cached = terminologyCache.get(cacheKey);
            if (cached != null) {
                results.put(termKey, cached);
                continue;
            }

            futures.add(CompletableFuture.runAsync(() -> {
                String out;
                try {
                    LookupParts lp = splitValueKey(termKey, ctx);
                    out = lookupSingleTerminology(lp.lookupTerm, lp.lookupContext);
                } catch (Exception e) {
                    logger.warn("[TerminologyService] {} key='{}': {}", logPrefix, termKey, e.toString());
                    out = "";
                }

                if (out == null) out = "";
                results.put(termKey, out);
                if (!out.isBlank()) {
                    terminologyCache.put(cacheKey, out);
                }
            }, executorService));
        }

        return new LookupBatch(results, futures);
    }

    private record LookupBatch(Map<String, String> results, List<CompletableFuture<Void>> futures) {}

    private static final class LookupParts {
        final String lookupTerm;
        final String lookupContext;

        LookupParts(String lookupTerm, String lookupContext) {
            this.lookupTerm = lookupTerm;
            this.lookupContext = lookupContext;
        }
    }

    private String lookupSingleTerminology(String term, String context) {
        String t = safe(term).trim();
        if (t.isEmpty()) return "";

        String searchTerm = padShortTerm(t, context);

        List<OntologyTermDTO> suggestions = null;
        int attempts = Math.max(1, snowstormLookupRetries + 1);

        for (int a = 1; a <= attempts; a++) {
            try {
                suggestions = rdfService.getSNOMEDTermSuggestions(searchTerm);
                if (suggestions == null || suggestions.isEmpty()) {
                    logger.debug("[TerminologyService] empty SNOMED suggestions for '{}' (ctx='{}', attempt {})",
                            searchTerm, context, a);
                }
                break;
            } catch (Exception e) {
                if (a >= attempts) {
                    logger.warn("[TerminologyService] snowstorm failed term='{}' ctx='{}' ({})",
                            shortStr(t), shortStr(context), e.toString());
                } else {
                    sleepQuietly(snowstormLookupRetryBackoffMs * a);
                }
            }
        }

        String code = firstCodeFromSuggestions(suggestions);
        if (!code.isEmpty()) return code;

        // Try paraphrases before the broader embedding-based candidate pass.
        ParaphraseService ps = (paraphraseProvider == null) ? null : paraphraseProvider.getIfAvailable();
        if (ps != null) {
            List<String> candidates = ps.paraphrase(searchTerm, 4);
            for (String cand : candidates) {
                if (cand == null || cand.isBlank()) continue;
                try {
                    List<OntologyTermDTO> s2 = rdfService.getSNOMEDTermSuggestions(cand);
                    String c2 = firstCodeFromSuggestions(s2);
                    if (!c2.isEmpty()) {
                        return c2;
                    }
                } catch (Exception ex) {
                    logger.debug("[TerminologyService] paraphrase lookup failed for '{}': {}", cand, ex.getMessage());
                }
            }
        }

        try {
            if (embeddingsClient != null) {
                String seed = searchTerm;
                float[] qVec = embeddingsClient.embed(seed);
                Set<String> seenIris = new HashSet<>();
                HashMap<OntologyTermDTO, Float> scores = new HashMap<>();

                // Mix the full term, context, and token-level probes to widen recall.
                Set<String> queries = new HashSet<>();
                queries.add(seed);
                if (!context.isBlank()) queries.add(context);
                queries.add((context + " " + t).trim());
                for (String tok : seed.split("\\s+")) {
                    String s = tok.trim();
                    if (s.length() >= 3) queries.add(s);
                }

                for (String q : queries) {
                    try {
                        List<OntologyTermDTO> cand = rdfService.getSNOMEDTermSuggestions(q);
                        if (cand == null) continue;
                        for (OntologyTermDTO ct : cand) {
                            if (ct == null || ct.getIri() == null) continue;
                            if (!seenIris.add(ct.getIri())) continue;
                            float[] labVec = embeddingsClient.embed(ct.getLabel() == null ? ct.getIri() : ct.getLabel());
                            float sim = 0f;
                            int n = Math.min(qVec.length, labVec.length);
                            for (int i = 0; i < n; i++) sim += qVec[i] * labVec[i];
                            scores.put(ct, sim);
                        }
                    } catch (Exception ex) {
                        // Ignore per-query failures and keep the broader candidate pass alive.
                    }
                }

                if (!scores.isEmpty()) {
                    Map.Entry<OntologyTermDTO, Float> best = scores.entrySet().stream().min((a, b) -> Float.compare(b.getValue(), a.getValue())).orElse(null);
                    if (best != null && best.getValue() > 0.65f) {
                        OntologyTermDTO chosen = best.getKey();
                        String ccode = (chosen.getIri() == null) ? "" : chosen.getIri().replaceAll(".*sct/", "").trim();
                        if (!ccode.isEmpty()) {
                            String label = chosen.getLabel();
                            label = (label == null) ? "" : label.trim();
                            return label.isEmpty() ? ccode : (label + " | " + ccode);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Embedding fallback is best-effort only.
        }

        if (!fallbackEnabled) return "";
        return generateFallbackTerminologyCode(t, context);
    }

    private String firstCodeFromSuggestions(List<OntologyTermDTO> suggestions) {
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


    private String generateFallbackTerminologyCode(String term, String context) {
        try {
            String t = safe(term);
            String ctx = safe(context);
            String searchTerm = (!ctx.isEmpty()) ? (ctx + " " + t) : t;

            try {
                if (embeddingsClient != null) embeddingsClient.embed(searchTerm);
            } catch (Exception e) {
                logger.debug("[TermLookup] Fallback embed call failed (non-critical): {}", e.getMessage());
            }

            int hashCode = (t + ctx).hashCode();
            long positiveHash = Math.abs((long) hashCode);
            return "CONCEPT_" + String.format("%09d", positiveHash % 1000000000L);
        } catch (Exception e) {
            return "";
        }
    }

    private String padShortTerm(String term, String context) {
        if (term == null) return "term";

        String trimmed = term.trim();
        if (trimmed.length() >= 3) return trimmed;

        String ctx = safe(context).trim();
        if (!ctx.isEmpty()) return trimmed + " " + ctx;

        return switch (trimmed.length()) {
            case 0 -> "term";
            case 1 -> trimmed + " value";
            case 2 -> trimmed + " term";
            default -> trimmed;
        };
    }

    private static void sleepQuietly(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String shortStr(String s) {
        String x = safe(s).trim();
        if (x.length() <= 60) return x;
        return x.substring(0, 60) + "...";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
