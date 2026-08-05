package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.Project;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;
import org.taniwha.service.KerberosService;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Populates the platform with default values
@Configuration
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";
    private static final String DEFAULT_EMAIL = "admin@mediata.local";
    private static final String DEFAULT_PROJECT_NAME = "Default Project";
    private static final String DEFAULT_PROJECT_DESCRIPTION = "Default project created on initialization";
    private static final String DEFAULT_PROJECT_BADGE = "default";
    private static final String STRATIF_PROJECT_NAME = "STRATIF-AI";
    private static final String STRATIF_PROJECT_DESCRIPTION = "STRATIF-AI deployment workspace";
    private static final String STRATIF_PROJECT_BADGE = "Active";
    private static final String DEFAULT_LOCAL_NODE_NAME = "Default Local";
    private static final List<String> DEFAULT_LOCAL_NODE_HOSTS = List.of(
            "localhost",
            "127.0.0.1",
            "mediata-default-node.local"
    );

    @Bean
    CommandLineRunner initDatabase(RoleRepository roleRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder,
                                   KerberosService kerberosService,
                                   ProjectRepository projectRepository,
                                   NodeRepository nodeRepository) {
        return args -> {
            logger.info("Initializing default data...");

            createRoleIfNotExists(roleRepository, "ROLE_ADMIN");
            createRoleIfNotExists(roleRepository, "ROLE_USER");

            createDefaultAdminIfNotExists(userRepository, roleRepository, passwordEncoder, kerberosService);

            createDefaultProjectIfNotExists(projectRepository);
            syncDefaultProjectNodes(projectRepository, nodeRepository);
            createStratifProject(projectRepository, nodeRepository);

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
            String principalName = kerberosService.getPrincipalName(DEFAULT_USERNAME, kerberosService.getRealm());
            kerberosService.createPrincipal(principalName, DEFAULT_PASSWORD);
            kerberosService.createKeytab(principalName);

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

    private void syncDefaultProjectNodes(ProjectRepository projectRepository, NodeRepository nodeRepository) {
        Project project = projectRepository.findByName(DEFAULT_PROJECT_NAME);
        if (project == null) {
            return;
        }

        Set<String> nodeIds = getProjectNodeIds(project);
        int originalSize = nodeIds.size();
        nodeRepository.findAll().stream()
                .filter(node -> node != null && node.getNodeId() != null && (node.getActive() == null || node.getActive()))
                .filter(this::isDefaultLocalNode)
                .map(node -> node.getNodeId().trim())
                .filter(nodeId -> !nodeId.isEmpty())
                .forEach(nodeIds::add);

        if (nodeIds.size() != originalSize) {
            project.setNodeIds(new ArrayList<>(nodeIds));
            projectRepository.save(project);
        }
    }

    private void createStratifProject(ProjectRepository projectRepository, NodeRepository nodeRepository) {
        Project project = projectRepository.findByName(STRATIF_PROJECT_NAME);
        boolean isNew = project == null;
        if (isNew) {
            project = new Project();
            project.setName(STRATIF_PROJECT_NAME);
            project.setDescription(STRATIF_PROJECT_DESCRIPTION);
            project.setBadge(STRATIF_PROJECT_BADGE);
        }

        if (project.getDescription() == null || project.getDescription().trim().isEmpty()) {
            project.setDescription(STRATIF_PROJECT_DESCRIPTION);
        }
        if (project.getBadge() == null || project.getBadge().trim().isEmpty()) {
            project.setBadge(STRATIF_PROJECT_BADGE);
        }

        Set<String> defaultNodeIds = getProjectNodeIds(projectRepository.findByName(DEFAULT_PROJECT_NAME));
        Set<String> nodeIds = new LinkedHashSet<>(project.getNodeIds() != null ? project.getNodeIds() : Collections.emptyList());
        nodeIds.removeAll(defaultNodeIds);
        nodeRepository.findAll().stream()
                .filter(node -> node != null && node.getNodeId() != null && (node.getActive() == null || node.getActive()))
                .filter(node -> !isDefaultLocalNode(node))
                .map(node -> node.getNodeId().trim())
                .filter(nodeId -> !nodeId.isEmpty())
                .filter(nodeId -> !defaultNodeIds.contains(nodeId))
                .forEach(nodeIds::add);

        Set<String> defaultLocalNodeIds = nodeRepository.findAll().stream()
                .filter(this::isDefaultLocalNode)
                .map(node -> node.getNodeId() == null ? "" : node.getNodeId().trim())
                .filter(nodeId -> !nodeId.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        nodeIds.removeAll(defaultLocalNodeIds);

        project.setNodeIds(new ArrayList<>(nodeIds));
        projectRepository.save(project);

        if (isNew) {
            logger.info("Created project: '{}'", STRATIF_PROJECT_NAME);
        } else {
            logger.info("Updated project '{}' with {} node(s)", STRATIF_PROJECT_NAME, project.getNodeIds().size());
        }
    }

    private Set<String> getProjectNodeIds(Project project) {
        if (project == null || project.getNodeIds() == null) {
            return Collections.emptySet();
        }

        return project.getNodeIds().stream()
                .filter(nodeId -> nodeId != null && !nodeId.trim().isEmpty())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isDefaultLocalNode(NodeInfo node) {
        if (node == null) {
            return false;
        }
        String name = node.getName();
        if (name != null && DEFAULT_LOCAL_NODE_NAME.equalsIgnoreCase(name.trim())) {
            return true;
        }

        String serviceUrl = node.getServiceUrl();
        if (serviceUrl == null || serviceUrl.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = new URI(serviceUrl.trim());
            String host = uri.getHost();
            return host != null && DEFAULT_LOCAL_NODE_HOSTS.stream()
                    .anyMatch(defaultHost -> defaultHost.equalsIgnoreCase(host));
        } catch (URISyntaxException e) {
            logger.debug("Could not parse node service URL '{}' while checking default project assignment", serviceUrl);
            return false;
        }
    }
}
