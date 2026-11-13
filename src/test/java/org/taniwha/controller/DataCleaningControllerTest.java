package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.CsvCleaningRequestDTO;
import org.taniwha.dto.CsvCleaningResponseDTO;
import org.taniwha.service.DataCleaningService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataCleaningControllerTest {

    private MockMvc mvc;
    private DataCleaningService dataCleaningService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        dataCleaningService = mock(DataCleaningService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DataCleaningController(dataCleaningService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void reformatCsv_returnsTransformedCsvPayload() throws Exception {
        when(dataCleaningService.reformatCsv(any())).thenReturn(
                new CsvCleaningResponseDTO("a,b\n1,2\n", 2, 2, "CSV reformatted successfully.")
        );

        CsvCleaningRequestDTO request = new CsvCleaningRequestDTO();
        request.setCsvText("a;b\n1;2\n");
        request.setInputDelimiter(";");
        request.setOutputDelimiter(",");
        request.setInputDecimalSeparator(",");
        request.setOutputDecimalSeparator(".");

        mvc.perform(post("/api/data-cleaning/csv/reformat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cleanedCsv").value("a,b\n1,2\n"))
                .andExpect(jsonPath("$.rowCount").value(2))
                .andExpect(jsonPath("$.columnCount").value(2))
                .andExpect(jsonPath("$.message").value("CSV reformatted successfully."));
    }

    @Test
    void reformatCsv_returnsBadRequestForInvalidOptions() throws Exception {
        when(dataCleaningService.reformatCsv(any()))
                .thenThrow(new IllegalArgumentException("inputDecimalSeparator must be '.' or ','."));

        mvc.perform(post("/api/data-cleaning/csv/reformat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
