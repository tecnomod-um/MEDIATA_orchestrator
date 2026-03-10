package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.taniwha.dto.OntologyTermDTO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Value("${terminology.fallback.enabled:true}")
    private boolean fallbackEnabled;

    public TerminologyLookupService(
            RDFService rdfService,
            EmbeddingsClient embeddingsClient,
            @Qualifier("llmExecutor") ObjectProvider<ExecutorService> executorProvider
    ) {
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
    }

    public static class TerminologyRequest {
        public final String term;
        public final String context;

        public TerminologyRequest(String term, String context) {
            this.term = term;
            this.context = context;
        }
    }

    public Map<String, String> batchLookupTerminology(List<TerminologyRequest> requests) {
        if (!snowstormEnabled || requests == null || requests.isEmpty()) return Collections.emptyMap();

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
                    logger.warn("[TerminologyService] lookup error key='{}': {}", termKey, e.toString());
                    out = "";
                }

                if (out == null) out = "";
                results.put(termKey, out);
                terminologyCache.put(cacheKey, out);
            }, executorService));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(snowstormTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logger.warn("[TerminologyService] batch timeout after {}s", snowstormTimeoutSeconds);
        } catch (Exception e) {
            logger.warn("[TerminologyService] batch interrupted: {}", e.toString());
        }

        return results;
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
        terminologyCache.put(cacheKey, out);
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
                    return label.isEmpty() ? code : (code + "|" + label);
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
