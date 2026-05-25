package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.dto.FieldMetadataDTO;
import org.taniwha.dto.OntologyTermDTO;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RDFServiceTest {

    private RDFService svc;
    private RestTemplate rest;
    private final String base = "http://api";

    @BeforeEach
    void setUp() {
        rest = mock(RestTemplate.class);
        RestTemplateConfig cfg = mock(RestTemplateConfig.class);
        when(cfg.getRestTemplate()).thenReturn(rest);

        svc = new RDFService(cfg);
        ReflectionTestUtils.setField(svc, "serviceUrl", base);
        ReflectionTestUtils.setField(svc, "pythonCsvDir", "target/tmpCsv");
        // Disable the Python-service health probe so that mock RestTemplate calls are
        // never short-circuited by the unreachable service guard in tests.
        ReflectionTestUtils.setField(svc, "pythonProbeEnabled", false);
        ReflectionTestUtils.setField(svc, "snowstormApiUrl", base);
    }

    @Test
    void getClassSuggestions_filtersByQuery_andHandlesErrors() {
        ResponseEntity<List<String>> ok = new ResponseEntity<>(
                Arrays.asList("FooType", "Other"),
                HttpStatus.OK
        );
        when(rest.exchange(
                eq(base + "/types"),
                eq(HttpMethod.GET),
                eq(null),
                any(ParameterizedTypeReference.class)
        )).thenReturn(ok);
        assertThat(svc.getClassSuggestions("")).hasSize(2);
        assertThat(svc.getClassSuggestions("foo"))
                .extracting(OntologyTermDTO::getLabel)
                .containsExactly("FooType");

        when(rest.exchange(
                eq(base + "/types"),
                eq(HttpMethod.GET),
                eq(null),
                any(ParameterizedTypeReference.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(svc.getClassSuggestions(null)).isEmpty();

        when(rest.exchange(
                eq(base + "/types"),
                eq(HttpMethod.GET),
                eq(null),
                any(ParameterizedTypeReference.class)
        )).thenThrow(new RestClientException("fail"));
        assertThat(svc.getClassSuggestions("x")).isEmpty();
    }

    @Test
    void getClassFields_parsesBodyOrReturnsEmptyOnError() {
        String type = "MyType";
        String url = base + "/form/" + type;
        ParameterizedTypeReference<List<Map<String, Object>>> ref = new ParameterizedTypeReference<List<Map<String, Object>>>() {
        };
        Map<String, Object> entry = new HashMap<>();
        entry.put("name", "f1");
        entry.put("optional", Boolean.TRUE);
        entry.put("type", "t1");

        ResponseEntity<List<Map<String, Object>>> ok =
                new ResponseEntity<>(Collections.singletonList(entry), HttpStatus.OK);

        when(rest.exchange(eq(url), eq(HttpMethod.GET), isNull(), eq(ref)))
                .thenReturn(ok);

        List<FieldMetadataDTO> fields = svc.getClassFields(type);
        assertThat(fields).hasSize(1)
                .first()
                .extracting(
                        FieldMetadataDTO::getName,
                        FieldMetadataDTO::isOptional,
                        FieldMetadataDTO::getType
                )
                .containsExactly("f1", true, "t1");

        when(rest.exchange(eq(url), eq(HttpMethod.GET), isNull(), eq(ref)))
                .thenReturn(new ResponseEntity<>(HttpStatus.BAD_REQUEST));
        assertThat(svc.getClassFields(type)).isEmpty();

        when(rest.exchange(eq(url), eq(HttpMethod.GET), isNull(), eq(ref)))
                .thenThrow(new RestClientException("oops"));
        assertThat(svc.getClassFields(type)).isEmpty();
    }

    @Test
    void getSNOMEDTermSuggestions_splitsAndHandlesErrors() {
        String q = "Q";
        Map<String, Object> item1 = new HashMap<>();
        item1.put("conceptId", "123");
        item1.put("term", "Foo");

        Map<String, Object> item2 = new HashMap<>();
        item2.put("conceptId", "456");
        item2.put("term", "456Only");

        Map<String, Object> body = new HashMap<>();
        body.put("items", Arrays.asList(item1, item2));

        ResponseEntity<Map> ok = new ResponseEntity<>(body, HttpStatus.OK);
        when(rest.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                eq(null),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(Collections.emptyMap(), HttpStatus.OK), ok);

        List<OntologyTermDTO> out = svc.getSNOMEDTermSuggestions(q);
        assertThat(out).hasSize(2);
        assertThat(out).extracting(OntologyTermDTO::getLabel)
                .containsExactly("Foo", "456Only");

        ArgumentCaptor<URI> uriCaptor = ArgumentCaptor.forClass(URI.class);
        verify(rest, times(2)).exchange(
                uriCaptor.capture(),
                eq(HttpMethod.GET),
                eq(null),
                eq(Map.class)
        );
        List<String> uris = uriCaptor.getAllValues().stream()
                .map(URI::toString)
                .toList();
        assertThat(uris.get(0))
                .startsWith(base + "/MAIN/concepts?")
                .contains("term=q", "activeFilter=true", "termActive=true", "limit=100");
        assertThat(uris.get(1))
                .startsWith(base + "/browser/MAIN/descriptions?")
                .contains("term=q", "active=true", "conceptActive=true", "groupByConcept=true", "limit=100");

        reset(rest);
        when(rest.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                eq(null),
                eq(Map.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE), new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(svc.getSNOMEDTermSuggestions(q)).isEmpty();

        reset(rest);
        when(rest.exchange(
                any(URI.class),
                eq(HttpMethod.GET),
                eq(null),
                eq(Map.class)
        )).thenThrow(new RestClientException("nope"));
        assertThat(svc.getSNOMEDTermSuggestions(q)).isEmpty();
    }

    @Test
    void writeCsv_and_generateRdf_roundTrip() throws IOException {
        Path csvDir = Paths.get("target/tmpCsv");
        if (Files.exists(csvDir)) {
            Files.walk(csvDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }

        String csv = "a,b,c\n1,2,3";
        svc.writeCsv(csv);

        Path input = csvDir.resolve("input.csv");
        String content = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
        assertThat(content).isEqualTo(csv);

        ResponseEntity<String> ok = new ResponseEntity<>("SOME_RDF", HttpStatus.OK);
        when(rest.getForEntity(base + "/generate-rdf", String.class)).thenReturn(ok);

        assertThat(svc.generateRdf()).isEqualTo("SOME_RDF");

        when(rest.getForEntity(base + "/generate-rdf", String.class))
                .thenReturn(new ResponseEntity<>("X", HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> svc.generateRdf())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("returned");
    }
}
