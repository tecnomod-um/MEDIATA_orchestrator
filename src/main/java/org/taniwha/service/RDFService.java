package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
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
import java.util.List;
import java.util.Map;
import java.util.Scanner;
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
        String url = serviceUrl + "/types";
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        List<OntologyTermDTO> suggestions = new ArrayList<>();
        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<String>>() {}
            );
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
                logger.error("Non-200 response from python service when fetching types: {} => {}",
                        url, response.getStatusCode());
            }
        } catch (RestClientException e) {
            logger.error("Error fetching types from python service: {}", url, e);
        }
        return suggestions;
    }

    public List<FieldMetadataDTO> getClassFields(String type) {
        String url = serviceUrl + "/form/" + type;
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
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
                logger.error("Non-200 from {}: {}", url, response.getStatusCode());
            }
        } catch (RestClientException e) {
            logger.error("Error fetching form fields for {}: {}", type, e.getMessage(), e);
        }
        return Collections.emptyList();
    }

    public List<OntologyTermDTO> getSNOMEDTermSuggestions(String query) {
        String q = safe(query).trim();
        if (q.isEmpty()) return Collections.emptyList();

        // If python service not reachable (and in cooldown), return blanks immediately.
        if (!pythonAllowAttemptOrProbe()) {
            logger.debug("[RDFService] Python service not reachable (cooldown). Returning 0 SNOMED suggestions for query='{}'", q);
            return Collections.emptyList();
        }

        String url = serviceUrl + "/term/" + q;
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();

        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<String>>() {}
            );

            markPythonSuccess();

            if (!response.getStatusCode().is2xxSuccessful()) {
                logger.debug("[RDFService] Non-2xx from python service for SNOMED terms: {} => {}",
                        url, response.getStatusCode());
                return Collections.emptyList();
            }

            List<String> terms = response.getBody();
            if (terms == null || terms.isEmpty()) return Collections.emptyList();

            List<OntologyTermDTO> suggestions = new ArrayList<>(terms.size());
            int idCounter = 1;

            for (String term : terms) {
                if (term == null) continue;
                String[] parts = term.split("\\|", 2);
                String code = safe(parts.length > 0 ? parts[0] : "").trim();
                String label = safe(parts.length > 1 ? parts[1] : term).trim();
                if (code.isEmpty() || label.isEmpty()) continue;

                suggestions.add(new OntologyTermDTO(
                        String.valueOf(idCounter++),
                        label,
                        "",
                        "http://snomed.info/sct/" + code
                ));
            }

            return suggestions;

        } catch (ResourceAccessException e) {
            markPythonFailure();
            logger.debug("[RDFService] Python service unreachable for SNOMED terms ({}). Returning 0 suggestions.",
                    shortMsg(e));
            return Collections.emptyList();

        } catch (RestClientException e) {
            markPythonFailure();
            logger.debug("[RDFService] Python service error for SNOMED terms ({}). Returning 0 suggestions.",
                    shortMsg(e));
            return Collections.emptyList();
        }
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
        String url = serviceUrl + "/generate-rdf";
        RestTemplate rt = restTemplateConfig.getRestTemplate();
        ResponseEntity<String> resp = rt.getForEntity(url, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Python RDF generation returned " + resp.getStatusCode());
        }
        logger.info("Python RDF generator said: {}", resp.getBody());
        return resp.getBody();
    }

    @Value("${rdfbuilder.probe.upProbeIntervalMs:10000}")
    private long pythonUpProbeIntervalMs;

    private final AtomicLong pyNextUpProbeEpochMs = new AtomicLong(0);

    private boolean pythonAllowAttemptOrProbe() {
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
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(pythonHealthUrl).openConnection();
            c.setConnectTimeout(pythonProbeConnectTimeoutMs);
            c.setReadTimeout(pythonProbeReadTimeoutMs);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private void markPythonSuccess() {
        pyState.set(PyState.UP);
        pyLastSuccess.set(Instant.now());
        pyConsecutiveFailures.set(0);
        pyNextProbeEpochMs.set(0);
    }

    private void markPythonFailure() {
        pyState.set(PyState.DOWN);
        pyLastFailure.set(Instant.now());

        int fails = pyConsecutiveFailures.incrementAndGet();
        long backoff = pythonBaseCooldownMs * (1L << Math.min(10, Math.max(0, fails - 1)));
        backoff = Math.min(pythonMaxCooldownMs, backoff);
        pyNextProbeEpochMs.set(System.currentTimeMillis() + backoff);
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
}
