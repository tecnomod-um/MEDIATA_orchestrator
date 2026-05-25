package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.dto.FieldMetadataDTO;
import org.taniwha.dto.OntologyTermDTO;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class RDFService {

    private static final Logger logger = LoggerFactory.getLogger(RDFService.class);

    private final RestTemplateConfig restTemplateConfig;

    @Value("${rdfbuilder.service.url}")
    private String serviceUrl;

    @Value("${rdfbuilder.csvpath}")
    private String pythonCsvDir;
    private enum PyState { UNKNOWN, UP, DOWN }

    @Value("${snowstorm.api.url:http://localhost:9100}")
    private String snowstormApiUrl;

    @Value("${snowstorm.api.branch:MAIN}")
    private String snowstormApiBranch;

    @Value("${rdfbuilder.healthUrl:${rdfbuilder.service.url}/types}")
    private String pythonHealthUrl;

    @Value("${rdfbuilder.probe.connectTimeoutMs:300}")
    private int pythonProbeConnectTimeoutMs;

    @Value("${rdfbuilder.probe.readTimeoutMs:600}")
    private int pythonProbeReadTimeoutMs;

    @Value("${rdfbuilder.probe.baseCooldownMs:2000}")
    private long pythonBaseCooldownMs;

    @Value("${rdfbuilder.probe.maxCooldownMs:60000}")
    private long pythonMaxCooldownMs;

    private final AtomicReference<PyState> pyState = new AtomicReference<>(PyState.UNKNOWN);
    private final AtomicReference<Instant> pyLastSuccess = new AtomicReference<>(null);
    private final AtomicReference<Instant> pyLastFailure = new AtomicReference<>(null);
    private final AtomicInteger pyConsecutiveFailures = new AtomicInteger(0);
    private final AtomicLong pyNextProbeEpochMs = new AtomicLong(0);

    @Autowired
    public RDFService(RestTemplateConfig restTemplateConfig) {
        this.restTemplateConfig = restTemplateConfig;
    }

    public List<OntologyTermDTO> getClassSuggestions(String query) {
        if (!pythonAllowAttemptOrProbe()) {
            logger.debug("[RDFService] rdf-builder unavailable; returning empty class list");
            return Collections.emptyList();
        }
        String url = serviceUrl + "/types";
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        List<OntologyTermDTO> suggestions = new ArrayList<>();
        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<>() {}
            );
            markPythonSuccess();
            if (response.getStatusCode().is2xxSuccessful()) {
                List<String> types = response.getBody();
                if (types != null) {
                    int idCounter = 1;
                    for (String type : types) {
                        if (query == null || query.isEmpty() || type.toLowerCase().contains(query.toLowerCase())) {
                            suggestions.add(new OntologyTermDTO(
                                    String.valueOf(idCounter++),
                                    type,
                                    "",
                                    "http://example.org/ontology/" + type
                            ));
                        }
                    }
                }
            } else {
                logger.debug("[RDFService] Non-200 from rdf-builder fetching types: {} => {}",
                        url, response.getStatusCode());
            }
        } catch (ResourceAccessException e) {
            markPythonFailure();
            logger.debug("[RDFService] rdf-builder unreachable fetching types: {}", shortMsg(e));
        } catch (RestClientException e) {
            markPythonFailure();
            logger.debug("[RDFService] rdf-builder error fetching types: {}", shortMsg(e));
        }
        return suggestions;
    }

    public List<FieldMetadataDTO> getClassFields(String type) {
        if (!pythonAllowAttemptOrProbe()) {
            logger.debug("[RDFService] rdf-builder unavailable; returning empty fields for type '{}'", type);
            return Collections.emptyList();
        }
        String url = serviceUrl + "/form/" + type;
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<>() {}
            );
            markPythonSuccess();
            if (response.getStatusCode().is2xxSuccessful()) {
                List<Map<String, Object>> body = response.getBody();
                List<FieldMetadataDTO> result = new ArrayList<>();
                if (body != null) {
                    for (Map<String, Object> m : body) {
                        String name = (String) m.get("name");
                        boolean optional = Boolean.TRUE.equals(m.get("optional"));
                        String typeStr = m.get("type") != null ? m.get("type").toString() : "";
                        result.add(new FieldMetadataDTO(name, optional, typeStr));
                    }
                }
                return result;
            } else {
                logger.debug("[RDFService] Non-200 from rdf-builder for class fields: {} => {}",
                        url, response.getStatusCode());
            }
        } catch (ResourceAccessException e) {
            markPythonFailure();
            logger.debug("[RDFService] rdf-builder unreachable for class fields: {}", shortMsg(e));
        } catch (RestClientException e) {
            markPythonFailure();
            logger.debug("[RDFService] rdf-builder error for class fields: {}", shortMsg(e));
        }
        return Collections.emptyList();
    }

    public List<OntologyTermDTO> getSNOMEDTermSuggestions(String query) {
        String q = safe(query).trim();
        if (q.isEmpty()) return Collections.emptyList();

        List<String> searchVariants = buildSearchVariants(q);
        if (searchVariants.isEmpty()) return Collections.emptyList();

        Set<String> seenConceptIds = new HashSet<>();
        List<OntologyTermDTO> suggestions = new ArrayList<>();

        for (String variant : searchVariants) {
            for (SearchRequest request : buildSearchRequests(variant)) {
                List<OntologyTermDTO> items = executeSnowstormSearch(request);
                for (OntologyTermDTO item : items) {
                    if (item == null) continue;
                    String iri = safe(item.getIri());
                    String conceptId = iri.replace("http://snomed.info/sct/", "").trim();
                    if (conceptId.isEmpty() || !seenConceptIds.add(conceptId)) continue;
                    suggestions.add(item);
                    if (suggestions.size() >= 12) return suggestions;
                }
            }
        }

        return suggestions;
    }

    public void writeCsv(String csvText) throws IOException {
        Path csvDir = Paths.get(pythonCsvDir);
        if (!Files.exists(csvDir)) Files.createDirectories(csvDir);
        Path inputCsv = csvDir.resolve("input.csv");
        Files.write(inputCsv,
                safe(csvText).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        logger.debug("Wrote mapping CSV to {}", inputCsv.toAbsolutePath());
    }

    public String generateRdf() {
        if (!pythonAllowAttemptOrProbe()) {
            throw new RuntimeException("rdf-builder service not available");
        }
        String url = serviceUrl + "/generate-rdf";
        RestTemplate rt = restTemplateConfig.getRestTemplate();
        try {
            ResponseEntity<String> resp = rt.getForEntity(url, String.class);
            markPythonSuccess();
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Python RDF generation returned " + resp.getStatusCode());
            }
            logger.info("Python RDF generator said: {}", resp.getBody());
            return resp.getBody();
        } catch (ResourceAccessException e) {
            markPythonFailure();
            throw new RuntimeException("rdf-builder service not available: " + shortMsg(e), e);
        }
    }

    @Value("${rdfbuilder.probe.enabled:true}")
    private boolean pythonProbeEnabled;

    @Value("${rdfbuilder.probe.upProbeIntervalMs:10000}")
    private long pythonUpProbeIntervalMs;

    private final AtomicLong pyNextUpProbeEpochMs = new AtomicLong(0);

    private boolean pythonAllowAttemptOrProbe() {
        if (!pythonProbeEnabled) return true;
        PyState state = pyState.get();
        long now = System.currentTimeMillis();

        if (state == PyState.UP) {
            long next = pyNextUpProbeEpochMs.get();
            if (now < next) return true;
            boolean ok = probePython();
            if (ok) {
                pyNextUpProbeEpochMs.set(now + pythonUpProbeIntervalMs);
                markPythonSuccess();
                return true;
            } else {
                markPythonFailure();
                return false;
            }
        }

        if (state == PyState.DOWN && now < pyNextProbeEpochMs.get()) return false;

        boolean ok = probePython();
        if (ok) {
            pyNextUpProbeEpochMs.set(now + pythonUpProbeIntervalMs);
            markPythonSuccess();
            return true;
        } else {
            markPythonFailure();
            return false;
        }
    }

    private boolean probePython() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(pythonHealthUrl).openConnection();
            c.setConnectTimeout(pythonProbeConnectTimeoutMs);
            c.setReadTimeout(pythonProbeReadTimeoutMs);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void markPythonSuccess() {
        PyState prev = pyState.get();
        pyState.set(PyState.UP);
        pyLastSuccess.set(Instant.now());
        pyConsecutiveFailures.set(0);
        pyNextProbeEpochMs.set(0);
        if (prev == PyState.DOWN) {
            logger.info("[RDFService] rdf-builder is now reachable; SNOMED terminology lookup enabled.");
        }
    }

    private void markPythonFailure() {
        PyState prev = pyState.get();
        pyState.set(PyState.DOWN);
        pyLastFailure.set(Instant.now());

        int fails = pyConsecutiveFailures.incrementAndGet();
        long backoff = pythonBaseCooldownMs * (1L << Math.min(10, Math.max(0, fails - 1)));
        backoff = Math.min(pythonMaxCooldownMs, backoff);
        pyNextProbeEpochMs.set(System.currentTimeMillis() + backoff);

        if (fails == 1 || prev == PyState.UP) {
            logger.info("[RDFService] rdf-builder not reachable (failure #{}). " +
                    "Terminology lookup will return empty until the service is available. " +
                    "Next probe in {}ms.", fails, backoff);
        }
    }

    private static String shortMsg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        m = m.replace("\r", " ").replace("\n", " ").trim();
        if (m.length() > 220) m = m.substring(0, 220) + "...";
        return m;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private List<OntologyTermDTO> executeSnowstormSearch(SearchRequest request) {
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        try {
            String url = request.toUrl();
            logger.info("[RDFService] SNOMED lookup term='{}' via concepts -> {}",
                    request.params.get("term"), url);
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    Map.class
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                return Collections.emptyList();
            }

            Object body = response.getBody();
            if (!(body instanceof Map<?, ?> mapBody)) {
                return Collections.emptyList();
            }

            Object itemsObj = mapBody.get("items");
            if (!(itemsObj instanceof List<?> items)) {
                return Collections.emptyList();
            }

            List<OntologyTermDTO> out = new ArrayList<>();
            int idCounter = 1;
            for (Object rawItem : items) {
                if (!(rawItem instanceof Map<?, ?> item)) continue;
                OntologyTermDTO dto = toOntologyTerm(item, idCounter);
                if (dto != null) {
                    out.add(dto);
                    idCounter++;
                }
            }
            return out;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private OntologyTermDTO toOntologyTerm(Map<?, ?> item, int idCounter) {
        String conceptId = extractConceptId(item);
        if (conceptId.isBlank()) return null;

        String label = extractLabel(item);
        if (label.isBlank()) return null;

        return new OntologyTermDTO(
                String.valueOf(idCounter),
                label,
                "",
                "http://snomed.info/sct/" + conceptId
        );
    }

    private String extractConceptId(Map<?, ?> item) {
        Object conceptObj = item.get("concept");
        if (conceptObj instanceof Map<?, ?> concept) {
            Object conceptId = concept.get("conceptId");
            if (conceptId != null && !conceptId.toString().isBlank()) {
                return conceptId.toString().trim();
            }
        }
        Object conceptId = item.get("conceptId");
        return conceptId == null ? "" : conceptId.toString().trim();
    }

    private String extractLabel(Map<?, ?> item) {
        Object term = item.get("term");
        if (term != null && !term.toString().isBlank()) {
            return term.toString().trim();
        }

        Object conceptObj = item.get("concept");
        if (conceptObj instanceof Map<?, ?> concept) {
            Object pt = concept.get("pt");
            if (pt instanceof Map<?, ?> ptMap) {
                Object ptTerm = ptMap.get("term");
                if (ptTerm != null && !ptTerm.toString().isBlank()) {
                    return ptTerm.toString().trim();
                }
            }
            Object fsn = concept.get("fsn");
            if (fsn instanceof Map<?, ?> fsnMap) {
                Object fsnTerm = fsnMap.get("term");
                if (fsnTerm != null && !fsnTerm.toString().isBlank()) {
                    return fsnTerm.toString().trim();
                }
            }
        }

        Object pt = item.get("pt");
        if (pt instanceof Map<?, ?> ptMap) {
            Object ptTerm = ptMap.get("term");
            if (ptTerm != null && !ptTerm.toString().isBlank()) {
                return ptTerm.toString().trim();
            }
        }

        Object fsn = item.get("fsn");
        if (fsn instanceof Map<?, ?> fsnMap) {
            Object fsnTerm = fsnMap.get("term");
            if (fsnTerm != null && !fsnTerm.toString().isBlank()) {
                return fsnTerm.toString().trim();
            }
        }

        Object idAndFsnTerm = item.get("idAndFsnTerm");
        if (idAndFsnTerm != null) {
            String raw = idAndFsnTerm.toString().trim();
            if (raw.length() > 2) return raw.substring(0, raw.length() - 2).trim();
        }

        return "";
    }

    private List<String> buildSearchVariants(String query) {
        String normalized = normalizeQuery(query);
        if (normalized.isBlank()) return Collections.emptyList();

        List<String> variants = new ArrayList<>();
        addVariant(variants, normalized);
        addVariant(variants, stripNumericTokens(normalized));

        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            String t = token.trim();
            if (t.length() >= 3 && !t.chars().allMatch(Character::isDigit)) {
                tokens.add(t);
            }
        }

        if (tokens.size() >= 2) {
            for (int size = tokens.size() - 1; size >= 2; size--) {
                for (int start = 0; start + size <= tokens.size(); start++) {
                    addVariant(variants, String.join(" ", tokens.subList(start, start + size)));
                }
            }
        }

        for (String token : tokens) {
            addVariant(variants, token);
        }

        return variants;
    }

    private void addVariant(List<String> variants, String candidate) {
        String normalized = candidate == null ? "" : candidate.replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank() && !variants.contains(normalized)) {
            variants.add(normalized);
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) return "";
        String text = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFKC);
        text = CAMEL_CASE_PATTERN.matcher(text).replaceAll("$1 $2");
        text = DIGIT_ALPHA_PATTERN.matcher(text).replaceAll("$1 $2");
        text = ALPHA_DIGIT_PATTERN.matcher(text).replaceAll("$1 $2");
        text = text.replace('_', ' ').replace('-', ' ').replace('/', ' ');
        text = NON_WORD_PATTERN.matcher(text).replaceAll(" ");
        return text.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private String stripNumericTokens(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder builder = new StringBuilder();
        for (String token : text.split("\\s+")) {
            if (!token.chars().allMatch(Character::isDigit)) {
                if (builder.length() > 0) builder.append(' ');
                builder.append(token);
            }
        }
        return builder.toString().trim();
    }

    private List<SearchRequest> buildSearchRequests(String query) {
        int tokenCount = query.isBlank() ? 0 : query.split("\\s+").length;
        int expandedLimit = tokenCount <= 1 ? 1000 : Math.min(Math.max(12 * 3, 20), 100);

        List<SearchRequest> requests = new ArrayList<>(1);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("term", query);
        params.put("activeFilter", "true");
        params.put("termActive", "true");
        params.put("limit", String.valueOf(expandedLimit));

        requests.add(new SearchRequest(snowstormConceptsUrl(), params));
        return requests;
    }

    private String snowstormConceptsUrl() {
        String baseUrl = safe(snowstormApiUrl).trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        String branch = safe(snowstormApiBranch).trim();
        if (branch.isBlank()) {
            branch = "MAIN";
        }
        while (branch.startsWith("/")) {
            branch = branch.substring(1);
        }
        while (branch.endsWith("/")) {
            branch = branch.substring(0, branch.length() - 1);
        }
        if (branch.isBlank()) {
            branch = "MAIN";
        }

        return baseUrl + "/" + branch + "/concepts";
    }

    private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("([a-z])([A-Z])");
    private static final Pattern ALPHA_DIGIT_PATTERN = Pattern.compile("([A-Za-z])([0-9])");
    private static final Pattern DIGIT_ALPHA_PATTERN = Pattern.compile("([0-9])([A-Za-z])");
    private static final Pattern NON_WORD_PATTERN = Pattern.compile("[^\\w\\s]");

    private static final class SearchRequest {
        final String url;
        final Map<String, ?> params;

        SearchRequest(String url, Map<String, ?> params) {
            this.url = url;
            this.params = params;
        }

        String toUrl() {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
            params.forEach(builder::queryParam);
            return builder.encode().toUriString();
        }
    }
}
