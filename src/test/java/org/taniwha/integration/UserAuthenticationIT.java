package org.taniwha.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for user authentication and authorization flows.
 * Tests the complete authentication lifecycle including user login and JWT token generation.
 */
@AutoConfigureMockMvc
public class UserAuthenticationIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private User testUser;
    private Role testRole;

    @BeforeEach
    void setUpUser() {
        // Clean up before each test
        userRepository.deleteAll();
        roleRepository.deleteAll();

        // Create test role
        testRole = new Role();
        testRole.setName("ADMIN");
        testRole = roleRepository.save(testRole);

        // Create test user with roles
        testUser = new User(
                null, // id - let MongoDB generate
                "testuser",
                passwordEncoder.encode("testPassword123"),
                "test@example.com",
                Collections.singletonList(testRole), // Add role
                null  // nodeIds
        );
        testUser = userRepository.save(testUser);
    }

    @Test
    void testSuccessfulLogin() throws Exception {
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", "testuser");
        loginRequest.put("password", "testPassword123");

        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", notNullValue()));
    }

    @Test
    void testLoginWithInvalidPassword() throws Exception {
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", "testuser");
        loginRequest.put("password", "wrongPassword");

        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testLoginWithNonExistentUser() throws Exception {
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", "nonexistent");
        loginRequest.put("password", "password");

        mockMvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testUserRegistration() throws Exception {
        Map<String, String> registerRequest = new HashMap<>();
        registerRequest.put("username", "newuser");
        registerRequest.put("password", "newPassword123");
        registerRequest.put("email", "newuser@example.com");

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string("User registered successfully"));

        // Verify user was saved to database
        User savedUser = userRepository.findByUsername("newuser");
        assertNotNull(savedUser);
    }

    @Test
    void testUserRegistrationWithDuplicateUsername() throws Exception {
        Map<String, String> registerRequest = new HashMap<>();
        registerRequest.put("username", "testuser"); // Already exists
        registerRequest.put("password", "password123");
        registerRequest.put("email", "another@example.com");

        mockMvc.perform(post("/api/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }
}
