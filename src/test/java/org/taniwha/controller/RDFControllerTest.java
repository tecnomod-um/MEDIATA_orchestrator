package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.FieldMetadataDTO;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.service.RDFService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RDFControllerTest {

    private MockMvc mvc;
    private RDFService rdfService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        rdfService = mock(RDFService.class);

        mvc = MockMvcBuilders
                .standaloneSetup(new RDFController(rdfService))
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(om),
                        new StringHttpMessageConverter(StandardCharsets.UTF_8)
                ).build();
    }

    @Test
    void getClassSuggestions_noQuery_returnsList() throws Exception {
        when(rdfService.getClassSuggestions(null))
                .thenReturn(Collections.singletonList(
                        new OntologyTermDTO("1", "Label", "", "iri1")));

        mvc.perform(get("/rdf/class"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("1"))
                .andExpect(jsonPath("$[0].label").value("Label"))
                .andExpect(jsonPath("$[0].iri").value("iri1"));
    }

    @Test
    void getClassSuggestions_withQuery_returnsFiltered() throws Exception {
        when(rdfService.getClassSuggestions("foo"))
                .thenReturn(Collections.singletonList(
                        new OntologyTermDTO("2", "Foo", "", "iri2")));
        mvc.perform(get("/rdf/class").param("query", "foo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("2"))
                .andExpect(jsonPath("$[0].label").value("Foo"))
                .andExpect(jsonPath("$[0].iri").value("iri2"));
    }

    @Test
    void getClassFields_returnsFields() throws Exception {
        when(rdfService.getClassFields("SomeType"))
                .thenReturn(Collections.singletonList(
                        new FieldMetadataDTO("field1", true, "typeA")));
        mvc.perform(get("/rdf/class/SomeType"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("field1"))
                .andExpect(jsonPath("$[0].optional").value(true))
                .andExpect(jsonPath("$[0].type").value("typeA"));
    }

    @Test
    void getSNOMEDTermSuggestions_returnsTerms() throws Exception {
        when(rdfService.getSNOMEDTermSuggestions("bar"))
                .thenReturn(Collections.singletonList(
                        new OntologyTermDTO("9", "Term", "", "iri9")));
        mvc.perform(get("/rdf/snomed").param("query", "bar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("9"))
                .andExpect(jsonPath("$[0].label").value("Term"))
                .andExpect(jsonPath("$[0].iri").value("iri9"));
    }

    @Test
    void uploadMappings_successfulCsvAndRdf() throws Exception {
        String csv = "a,b,c";
        doNothing().when(rdfService).writeCsv(csv);
        when(rdfService.generateRdf()).thenReturn("RDF-DATA");
        mvc.perform(post("/rdf/semanticalignment")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csvSaved").value(true))
                .andExpect(jsonPath("$.csvMessage").value("CSV saved successfully"))
                .andExpect(jsonPath("$.rdfGenerated").value(true))
                .andExpect(jsonPath("$.rdfMessage").value("RDF-DATA"));
    }

    @Test
    void uploadMappings_csvWriteFails_skipsRdf() throws Exception {
        String csv = "x,y";
        doThrow(new IOException("disk full")).when(rdfService).writeCsv(csv);
        mvc.perform(post("/rdf/semanticalignment")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csvSaved").value(false))
                .andExpect(jsonPath("$.csvMessage").value("Failed to save CSV: disk full"))
                .andExpect(jsonPath("$.rdfGenerated").value(false))
                .andExpect(jsonPath("$.rdfMessage")
                        .value("Skipped RDF generation because CSV write failed"));
    }

    @Test
    void uploadMappings_rdfGenerationFails_reportsError() throws Exception {
        String csv = "1,2";
        doNothing().when(rdfService).writeCsv(csv);
        when(rdfService.generateRdf()).thenThrow(new RuntimeException("syntax error"));

        mvc.perform(post("/rdf/semanticalignment")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csvSaved").value(true))
                .andExpect(jsonPath("$.csvMessage").value("CSV saved successfully"))
                .andExpect(jsonPath("$.rdfGenerated").value(false))
                .andExpect(jsonPath("$.rdfMessage")
                        .value("RDF generation failed: syntax error"));
    }
}
