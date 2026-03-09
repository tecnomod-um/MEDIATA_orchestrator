package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.model.ErrorLog;
import org.taniwha.repository.ErrorLogRepository;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ErrorLogControllerTest {

    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();
    private ErrorLogRepository errorLogRepository;

    @BeforeEach
    void setup() {
        errorLogRepository = mock(ErrorLogRepository.class);
        this.mvc = MockMvcBuilders
                .standaloneSetup(new ErrorLogController(errorLogRepository))
                .build();
    }

    @Test
    void logError_returnsOkAndMessage() throws Exception {
        ErrorLogDTO dto = new ErrorLogDTO();
        dto.setError("Something went wrong");
        dto.setInfo("Extra info");
        dto.setTimestamp("123456789");

        when(errorLogRepository.save(any(ErrorLog.class)))
                .thenReturn(new ErrorLog("id1", dto.getError(), dto.getInfo(), dto.getTimestamp()));

        mvc.perform(post("/api/error")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("Error logged successfully"));
    }

    @Test
    void getAllLogs_returnsLogList() throws Exception {
        List<ErrorLog> logs = Arrays.asList(
                new ErrorLog("id1", "Error A", "Info A", "111"),
                new ErrorLog("id2", "Error B", "Info B", "222")
        );
        when(errorLogRepository.findAll()).thenReturn(logs);

        mvc.perform(get("/api/error/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("id1"))
                .andExpect(jsonPath("$[0].error").value("Error A"))
                .andExpect(jsonPath("$[0].info").value("Info A"))
                .andExpect(jsonPath("$[0].timestamp").value("111"))
                .andExpect(jsonPath("$[1].id").value("id2"))
                .andExpect(jsonPath("$[1].error").value("Error B"))
                .andExpect(jsonPath("$[1].info").value("Info B"))
                .andExpect(jsonPath("$[1].timestamp").value("222"));
    }

    @Test
    void getAllLogs_emptyList_returnsEmptyArray() throws Exception {
        when(errorLogRepository.findAll()).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/error/all"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
