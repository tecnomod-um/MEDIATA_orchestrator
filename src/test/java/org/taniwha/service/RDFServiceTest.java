package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    }

    @Test
    void getClassSuggestions_filtersByQuery_andHandlesErrors() {
        ResponseEntity<List> ok = new ResponseEntity<>(
                Arrays.asList("FooType", "Other"),
                HttpStatus.OK
        );
        when(rest.getForEntity(base + "/types", List.class)).thenReturn(ok);
        assertThat(svc.getClassSuggestions("")).hasSize(2);
        assertThat(svc.getClassSuggestions("foo"))
                .extracting(OntologyTermDTO::getLabel)
                .containsExactly("FooType");

        when(rest.getForEntity(base + "/types", List.class))
                .thenReturn(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(svc.getClassSuggestions(null)).isEmpty();

        when(rest.getForEntity(base + "/types", List.class))
                .thenThrow(new RestClientException("fail"));
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
        ResponseEntity<List> ok = new ResponseEntity<>(
                Arrays.asList("123|Foo", "456Only"),
                HttpStatus.OK
        );
        when(rest.getForEntity(base + "/term/" + q, List.class)).thenReturn(ok);

        List<OntologyTermDTO> out = svc.getSNOMEDTermSuggestions(q);
        assertThat(out).hasSize(2);
        assertThat(out).extracting(OntologyTermDTO::getLabel)
                .containsExactly("Foo", "456Only");

        when(rest.getForEntity(base + "/term/" + q, List.class))
                .thenReturn(new ResponseEntity<>(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(svc.getSNOMEDTermSuggestions(q)).isEmpty();

        when(rest.getForEntity(base + "/term/" + q, List.class))
                .thenThrow(new RestClientException("nope"));
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
