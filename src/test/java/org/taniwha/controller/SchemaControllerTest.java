package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.service.SchemaService;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaControllerTest {

    private MockMvc mvc;
    private SchemaService schemaService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        schemaService = mock(SchemaService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new SchemaController(schemaService))
                .build();
    }

    @Test
    void saveSchema_missingField_returns400() throws Exception {
        Map<String, Object> req = Collections.emptyMap();
        mvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Missing 'schema' field in request body"));

        verifyNoInteractions(schemaService);
    }

    @Test
    void saveSchema_success_returns200() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("schema", Collections.singletonMap("k", "v"));
        mvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Schema saved successfully"));

        verify(schemaService).saveSchema(req.get("schema"));
    }

    @Test
    void saveSchema_serviceThrows_returns500() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("schema", Collections.singletonMap("foo", "bar"));
        doThrow(new RuntimeException("boom")).when(schemaService).saveSchema(any());
        mvc.perform(post("/nodes/schema")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error")
                        .value("Failed to save schema: boom"));
        verify(schemaService).saveSchema(req.get("schema"));
    }

    @Test
    void getSchema_noSchema_returns404() throws Exception {
        when(schemaService.getSchema()).thenReturn(null);
        mvc.perform(get("/nodes/schema"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error")
                        .value("No schema found"));
    }

    @Test
    void getSchema_withSchema_returns200() throws Exception {
        when(schemaService.getSchema()).thenReturn("{\"hello\":1}");
        mvc.perform(get("/nodes/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema")
                        .value("{\"hello\":1}"));
    }

    @Test
    void removeSchema_always_returns200() throws Exception {
        mvc.perform(delete("/nodes/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("Schema removed successfully"));
        verify(schemaService).removeSchema();
    }
}
