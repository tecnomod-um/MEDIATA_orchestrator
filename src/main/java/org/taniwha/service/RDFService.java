package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.dto.FieldMetadataDTO;
import org.taniwha.dto.OntologyTermDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class RDFService {

    private static final Logger logger = LoggerFactory.getLogger(RDFService.class);

    private final RestTemplateConfig restTemplateConfig;

    @Value("${rdfbuilder.service.url}")
    private String serviceUrl;
    @Value("${rdfbuilder.csvpath}")
    private String pythonCsvDir;

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
                    new ParameterizedTypeReference<List<String>>() {
                    }
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                List<String> types = response.getBody();
                if (types != null) {
                    int idCounter = 1;
                    for (String type : types) {
                        // Filter based on the query (if provided)
                        if (query == null || query.isEmpty() || type.toLowerCase().contains(query.toLowerCase())) {
                            suggestions.add(new OntologyTermDTO(
                                    String.valueOf(idCounter++),
                                    type,
                                    "",
                                    "http://example.org/ontology/" + type));
                        }
                    }
                }
            } else {
                logger.error("Non-200 response from python service when fetching types: {} => {}",
                        url, response.getStatusCode());
            }
        } catch (RestClientException e) {
            logger.error("Error fetching types from python service: " + url, e);
        }
        return suggestions;
    }
/*
    public List<String> getClassFields(String type) {
        String url = serviceUrl + "/form/" + type;
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info(response.getBody().toString());
                List<Map<String, Object>> fieldsObjects = response.getBody();
                List<String> fields = new ArrayList<>();
                if (fieldsObjects != null) {
                    for (Map<String, Object> fieldObj : fieldsObjects) {
                        // Adjust the key below to match your JSON structure.
                        Object field = fieldObj.get("name");
                        if (field != null) fields.add(field.toString());
                    }
                }
                return fields;
            } else {
                logger.error("Non-200 response from python service when fetching form fields for type {}: {} => {}",
                        type, url, response.getStatusCode());
            }
        } catch (RestClientException e) {
            logger.error("Error fetching form fields for type: " + type + " from python service: " + url, e);
        }
        return new ArrayList<>();
    }
*/

    // RDFService.java
    public List<FieldMetadataDTO> getClassFields(String type) {
        String url = serviceUrl + "/form/" + type;
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        try {
            ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                    url, HttpMethod.GET, null,
                    new ParameterizedTypeReference<List<Map<String, Object>>>() {
                    }
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
        String url = serviceUrl + "/term/" + query;
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        // Explicitly create ArrayList to ensure type safety
        ArrayList<OntologyTermDTO> suggestions = new ArrayList<>();
        try {
            ResponseEntity<List<String>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<String>>() {
                    }
            );
            if (response.getStatusCode().is2xxSuccessful()) {
                List<String> terms = response.getBody();
                logger.info(response.getBody().toString());
                if (terms != null) {
                    int idCounter = 1;
                    for (String term : terms) {
                        String[] parts = term.split("\\|", 2);
                        String code = parts[0].trim();
                        String label = (parts.length > 1) ? parts[1].trim() : term;
                        // Explicitly create each DTO to ensure proper type
                        OntologyTermDTO dto = new OntologyTermDTO(
                                String.valueOf(idCounter++),
                                label,
                                "",
                                "http://snomed.info/sct/" + code
                        );
                        suggestions.add(dto);
                    }
                }
            } else {
                logger.error("Non-200 response from python service when fetching SNOMED terms: {} => {}", url, response.getStatusCode());
            }
        } catch (RestClientException e) {
            logger.error("Error fetching SNOMED terms from python service: " + url, e);
        }
        // Return the explicitly typed ArrayList
        return suggestions;
    }
/*
    public void processSemanticAlignment(String csvText) {
        logger.info("CSV payload:\n{}", csvText);

        try {
            Path csvDir = Paths.get(pythonCsvDir);
            if (!Files.exists(csvDir))
                Files.createDirectories(csvDir);
            Path inputCsv = csvDir.resolve("input.csv");
            Files.write(inputCsv,
                    csvText.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            logger.debug("Wrote mapping CSV to {}", inputCsv.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to write mapping CSV to {}", pythonCsvDir, e);
            throw new RuntimeException(e);
        }

        String url = serviceUrl + "/generate-rdf";
        RestTemplate rt = restTemplateConfig.getRestTemplate();
        try {
            ResponseEntity<String> resp = rt.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Python RDF generation returned " + resp.getStatusCode());
            }
            logger.info("Python RDF generator said: {}", resp.getBody());
        } catch (RestClientException ex) {
            logger.error("Error calling Python service at {}", url, ex);
            throw ex;
        }
    }
    */

    public void writeCsv(String csvText) throws IOException {
        Path csvDir = Paths.get(pythonCsvDir);
        if (!Files.exists(csvDir)) Files.createDirectories(csvDir);
        Path inputCsv = csvDir.resolve("input.csv");
        Files.write(inputCsv,
                csvText.getBytes(StandardCharsets.UTF_8),
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

}
