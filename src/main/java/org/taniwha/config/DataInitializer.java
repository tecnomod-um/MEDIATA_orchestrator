package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.taniwha.model.Project;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;
import org.taniwha.service.KerberosService;

import java.util.Collections;

@Configuration
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final String DEFAULT_EMAIL = "admin@mediata.local";
    private static final String DEFAULT_PROJECT_NAME = "Default Project";
    private static final String DEFAULT_PROJECT_DESCRIPTION = "Default project created on initialization";
    private static final String DEFAULT_PROJECT_BADGE = "default";

    @Bean
    CommandLineRunner initDatabase(RoleRepository roleRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   @Autowired(required = false) KerberosService kerberosService,
                                   ProjectRepository projectRepository) {
        return args -> {
            logger.info("Initializing default data...");

            createRoleIfNotExists(roleRepository, "ROLE_ADMIN");
            createRoleIfNotExists(roleRepository, "ROLE_USER");

            createDefaultAdminIfNotExists(userRepository, roleRepository, passwordEncoder, kerberosService);
            
            createDefaultProjectIfNotExists(projectRepository);

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

    private void createDefaultAdminIfNotExists(UserRepository userRepository,
                                               RoleRepository roleRepository,
                                               PasswordEncoder passwordEncoder,
                                               KerberosService kerberosService) {
        User existingUser = userRepository.findByUsername(DEFAULT_USERNAME);
        if (existingUser != null) {
            logger.debug("Default admin user already exists");
            return;
        }

        try {
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

            // IMPORTANT: Kerberos principal must be created with the RAW password
            if (kerberosService != null) {
                String principalName = kerberosService.getPrincipalName(DEFAULT_USERNAME, kerberosService.getRealm());
                kerberosService.createPrincipal(principalName, DEFAULT_PASSWORD);
                kerberosService.createKeytab(principalName);
            } else {
                logger.debug("Kerberos service not available, skipping principal creation for default admin");
            }

            logger.warn("Created default admin user - Username: '{}', Password: '{}'. CHANGE THIS IN PRODUCTION!",
                    DEFAULT_USERNAME, DEFAULT_PASSWORD);

        } catch (KrbException e) {
            logger.error("Failed to create Kerberos principal for default admin user", e);
            logger.warn("Default admin user created in MongoDB but Kerberos setup failed.");
        }
    }

    private void createDefaultProjectIfNotExists(ProjectRepository projectRepository) {
        Project existingProject = projectRepository.findByName(DEFAULT_PROJECT_NAME);
        if (existingProject != null) {
            logger.debug("Default project already exists");
            return;
        }

        Project defaultProject = new Project();
        defaultProject.setName(DEFAULT_PROJECT_NAME);
        defaultProject.setDescription(DEFAULT_PROJECT_DESCRIPTION);
        defaultProject.setBadge(DEFAULT_PROJECT_BADGE);

        projectRepository.save(defaultProject);
        logger.info("Created default project: '{}'", DEFAULT_PROJECT_NAME);
    }
}
