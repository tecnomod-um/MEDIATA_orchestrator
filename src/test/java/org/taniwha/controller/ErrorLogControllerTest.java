package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.ErrorLogDTO;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ErrorLogControllerTest {

    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() {
        this.mvc = MockMvcBuilders
                .standaloneSetup(new ErrorLogController())
                .build();
    }

    @Test
    void logError_returnsOkAndMessage() throws Exception {
        ErrorLogDTO dto = new ErrorLogDTO();
        dto.setError("Something went wrong");
        dto.setInfo("Extra info");
        dto.setTimestamp("123456789");

        mvc.perform(post("/api/error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("Error logged successfully"));
    }
}
