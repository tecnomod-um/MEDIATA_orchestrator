package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.dto.LoginRequestDTO;
import org.taniwha.dto.LoginResponseDTO;
import org.taniwha.dto.RegisterRequestDTO;
import org.taniwha.service.UserService;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class UserControllerTest {

    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();
    private UserService userService;
    private MongoDatabase mongoDb;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        MongoClient mongoClient = mock(MongoClient.class);
        mongoDb = mock(MongoDatabase.class, RETURNS_DEEP_STUBS);

        when(mongoClient.getDatabase("taniwha")).thenReturn(mongoDb);
        mvc = MockMvcBuilders
                .standaloneSetup(new UserController(userService, mongoClient))
                .build();
    }

    private RegisterRequestDTO newRegisterDto() {
        RegisterRequestDTO dto = new RegisterRequestDTO();
        dto.setUsername("newbie");
        dto.setPassword("pw");
        dto.setEmail("e@x");
        dto.setRoles(Collections.singletonList("ROLE_USER"));
        return dto;
    }

    @Test
    void login_mongoDown_returns500() throws Exception {
        when(mongoDb.listCollectionNames().first())
                .thenThrow(new RuntimeException("mongo down"));
        LoginRequestDTO req = new LoginRequestDTO();
        req.setUsername("alice");
        req.setPassword("pw");

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.token")
                        .value("Internal Server Error: MongoDB connection failed"));
        verifyNoInteractions(userService);
    }

    @Test
    void login_success_returns200() throws Exception {
        when(mongoDb.listCollectionNames().first()).thenReturn("ok");
        when(userService.loginUser("alice", "pw"))
                .thenReturn(new LoginResponseDTO("JWT", "TGT"));

        LoginRequestDTO req = new LoginRequestDTO();
        req.setUsername("alice");
        req.setPassword("pw");

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("JWT"))
                .andExpect(jsonPath("$.tgt").value("TGT"));
    }

    @Test
    void login_badCreds_returns401() throws Exception {
        when(mongoDb.listCollectionNames().first()).thenReturn("ok");
        when(userService.loginUser("bob", "wrong"))
                .thenThrow(new RuntimeException("bad creds"));

        LoginRequestDTO req = new LoginRequestDTO();
        req.setUsername("bob");
        req.setPassword("wrong");

        mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token")
                        .value("Invalid username or password"));
    }

    @Test
    void register_taken_returns409() throws Exception {
        when(userService.isUsernameTaken("newbie")).thenReturn(true);

        RegisterRequestDTO dto = newRegisterDto();

        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(content().string("Username is already taken"));

        verify(userService, never()).registerUser(any(), any());
    }

    @Test
    void register_success_returns200() throws Exception {
        when(userService.isUsernameTaken("newbie")).thenReturn(false);

        RegisterRequestDTO dto = newRegisterDto();
        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(content().string("User registered successfully"));
        ArgumentCaptor<RegisterRequestDTO> capt = ArgumentCaptor.forClass(RegisterRequestDTO.class);
        verify(userService).registerUser(capt.capture(), isNull());

        assertThat(capt.getValue().getUsername()).isEqualTo("newbie");
        assertThat(capt.getValue().getEmail()).isEqualTo("e@x");
    }

    @Test
    void register_serviceError_returns500() throws Exception {
        when(userService.isUsernameTaken("newbie")).thenReturn(false);
        doThrow(new RuntimeException("boom"))
                .when(userService).registerUser(any(RegisterRequestDTO.class), isNull());

        RegisterRequestDTO dto = newRegisterDto();
        mvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(dto)))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .string("Internal Server Error: Registration failed"));
    }
}
