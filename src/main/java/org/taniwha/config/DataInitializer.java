package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;

import java.util.Collections;

/**
 * Initializes default data in the database on application startup.
 * Creates default roles and a default admin user if they don't exist.
 */
@Configuration
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final String DEFAULT_EMAIL = "admin@mediata.local";

    @Bean
    CommandLineRunner initDatabase(RoleRepository roleRepository, 
                                     UserRepository userRepository,
                                     PasswordEncoder passwordEncoder) {
        return args -> {
            logger.info("Initializing default data...");

            // Create default roles if they don't exist
            createRoleIfNotExists(roleRepository, "ROLE_ADMIN");
            createRoleIfNotExists(roleRepository, "ROLE_USER");

            // Create default admin user if it doesn't exist
            createDefaultUserIfNotExists(userRepository, roleRepository, passwordEncoder);

            logger.info("Data initialization complete");
        };
    }

    private void createRoleIfNotExists(RoleRepository roleRepository, String roleName) {
        Role existingRole = roleRepository.findByName(roleName);
        if (existingRole == null) {
            Role role = new Role();
            role.setName(roleName);
            roleRepository.save(role);
            logger.info("Created default role: {}", roleName);
        } else {
            logger.debug("Role already exists: {}", roleName);
        }
    }

    private void createDefaultUserIfNotExists(UserRepository userRepository, 
                                               RoleRepository roleRepository,
                                               PasswordEncoder passwordEncoder) {
        User existingUser = userRepository.findByUsername(DEFAULT_USERNAME);
        if (existingUser == null) {
            Role adminRole = roleRepository.findByName("ROLE_ADMIN");
            
            String encodedPassword = passwordEncoder.encode(DEFAULT_PASSWORD);
            User defaultUser = new User(
                null,
                DEFAULT_USERNAME,
                encodedPassword,
                DEFAULT_EMAIL,
                Collections.singletonList(adminRole),
                Collections.emptyList()
            );
            
            userRepository.save(defaultUser);
            logger.warn("Created default admin user - Username: '{}', Password: '{}'. PLEASE CHANGE THIS PASSWORD IN PRODUCTION!", 
                       DEFAULT_USERNAME, DEFAULT_PASSWORD);
        } else {
            logger.debug("Default admin user already exists");
        }
    }
}
