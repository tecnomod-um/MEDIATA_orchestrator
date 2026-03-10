package org.taniwha.controller;

import com.mongodb.client.MongoClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.taniwha.dto.LoginRequestDTO;
import org.taniwha.dto.LoginResponseDTO;
import org.taniwha.dto.RegisterRequestDTO;
import org.taniwha.service.UserService;

// Requests related to the user system
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;
    private final MongoClient mongoClient;

    public UserController(UserService userService, MongoClient mongoClient) {
        this.userService = userService;
        this.mongoClient = mongoClient;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDTO> login(@RequestBody LoginRequestDTO loginRequest) {
        logger.debug("Login attempt for user: {}", loginRequest.getUsername());

        try {
            mongoClient.getDatabase("taniwha").listCollectionNames().first();
            logger.debug("MongoDB connection is OK");
        } catch (Exception e) {
            logger.error("MongoDB connection failed", e);
            return ResponseEntity.status(500)
                    .body(new LoginResponseDTO("Internal Server Error: MongoDB connection failed", null));
        }

        try {
            LoginResponseDTO loginResponse =
                    userService.loginUser(loginRequest.getUsername(), loginRequest.getPassword());
            return ResponseEntity.ok(loginResponse);

        } catch (BadCredentialsException ex) {
            logger.warn("Login failed (wrong credentials) for user {}", loginRequest.getUsername());
            return ResponseEntity.status(401)
                    .body(new LoginResponseDTO("Invalid username or password", null));

        } catch (Exception ex) {
            // keep stack trace for real errors
            logger.error("Login failed for user {}", loginRequest.getUsername(), ex);
            return ResponseEntity.status(500)
                    .body(new LoginResponseDTO("Internal Server Error", null));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(
            @RequestBody RegisterRequestDTO registerRequest,
            @RequestHeader(value = "Authorization", required = false) String token) {
        logger.debug("Registration attempt for user: {}", registerRequest.getUsername());

        if (userService.isUsernameTaken(registerRequest.getUsername()))
            return ResponseEntity.status(409).body("Username is already taken");
        try {
            userService.registerUser(registerRequest, token);
            logger.info("New user registered: {}", registerRequest.getUsername());
            return ResponseEntity.ok("User registered successfully");
        } catch (Exception e) {
            logger.error("Registration failed for user {}", registerRequest.getUsername(), e);
            return ResponseEntity.status(500).body("Internal Server Error: Registration failed");
        }
    }
}
