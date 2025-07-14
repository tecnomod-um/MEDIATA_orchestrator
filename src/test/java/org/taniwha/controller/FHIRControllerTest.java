package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.service.FHIRService;

import java.io.IOException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FHIRControllerTest {

    private MockMvc mvc;
    private FHIRService fhirService;

    @BeforeEach
    void setup() {
        fhirService = mock(FHIRService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FHIRController(fhirService)).build();
    }

    @Test
    void createClusters_success_returnsOk() throws Exception {
        String payload = "{\"foo\":\"bar\"}";
        String expectedResponse = "{\"clusters\":[1,2,3]}";

        when(fhirService.processClusters(payload)).thenReturn(expectedResponse);
        mvc.perform(post("/fhir/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(content().string(expectedResponse));
        verify(fhirService).processClusters(payload);
    }

    @Test
    void createClusters_ioException_returnsInternalServerError() throws Exception {
        String payload = "{}";

        when(fhirService.processClusters(payload)).thenThrow(new IOException("oops"));
        mvc.perform(post("/fhir/clusters")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("{\"error\":\"Could not load cluster response\"}"));

        verify(fhirService).processClusters(payload);
    }
}
