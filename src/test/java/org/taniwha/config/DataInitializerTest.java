package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.CommandLineRunner;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DataInitializerTest {

    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private KerberosService kerberosService;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private NodeRepository nodeRepository;

    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dataInitializer = new DataInitializer();
    }

    @Test
    void initDatabase_createsDefaultProject_whenNotExists() throws Exception {
        when(roleRepository.findByName(anyString())).thenReturn(new Role());
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);
        when(projectRepository.findByName("Default Project")).thenReturn(null);
        when(nodeRepository.findAll()).thenReturn(List.of());

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, times(2)).save(projectCaptor.capture());

        Project savedProject = projectCaptor.getAllValues().stream()
                .filter(project -> "Default Project".equals(project.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(savedProject.getName()).isEqualTo("Default Project");
        assertThat(savedProject.getDescription()).isEqualTo("Default project created on initialization");
        assertThat(savedProject.getBadge()).isEqualTo("default");

        Project stratifProject = projectCaptor.getAllValues().stream()
                .filter(project -> "STRATIF-AI".equals(project.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(stratifProject.getDescription()).isEqualTo("STRATIF-AI deployment workspace");
        assertThat(stratifProject.getBadge()).isEqualTo("Active");
        assertThat(stratifProject.getNodeIds()).isEmpty();
    }

    @Test
    void initDatabase_doesNotCreateDefaultProject_whenAlreadyExists() throws Exception {
        when(roleRepository.findByName(anyString())).thenReturn(new Role());
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);

        Project existingProject = new Project();
        existingProject.setName("Default Project");
        when(projectRepository.findByName("Default Project")).thenReturn(existingProject);
        when(nodeRepository.findAll()).thenReturn(List.of());

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        verify(projectRepository, never()).save(argThat(project -> "Default Project".equals(project.getName())));
    }

    @Test
    void initDatabase_assignsActiveNodesToStratifProject() throws Exception {
        when(roleRepository.findByName(anyString())).thenReturn(new Role());
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);
        when(projectRepository.findByName("Default Project")).thenReturn(new Project());
        when(projectRepository.findByName("STRATIF-AI")).thenReturn(null);

        NodeInfo activeNode = new NodeInfo();
        activeNode.setNodeId("active-node");
        activeNode.setActive(true);
        NodeInfo legacyActiveNode = new NodeInfo();
        legacyActiveNode.setNodeId("legacy-active-node");
        NodeInfo inactiveNode = new NodeInfo();
        inactiveNode.setNodeId("inactive-node");
        inactiveNode.setActive(false);
        when(nodeRepository.findAll()).thenReturn(List.of(activeNode, legacyActiveNode, inactiveNode));

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        verify(projectRepository).save(argThat(project ->
                "STRATIF-AI".equals(project.getName())
                        && project.getNodeIds().equals(List.of("active-node", "legacy-active-node"))));
    }

    @Test
    void initDatabase_keepsDefaultProjectNodesOutOfStratifProject() throws Exception {
        when(roleRepository.findByName(anyString())).thenReturn(new Role());
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);

        Project defaultProject = new Project();
        defaultProject.setName("Default Project");
        defaultProject.setNodeIds(List.of("default-node"));

        Project stratifProject = new Project();
        stratifProject.setName("STRATIF-AI");
        stratifProject.setNodeIds(List.of("default-node", "remote-node"));

        when(projectRepository.findByName("Default Project")).thenReturn(defaultProject);
        when(projectRepository.findByName("STRATIF-AI")).thenReturn(stratifProject);

        NodeInfo defaultNode = new NodeInfo();
        defaultNode.setNodeId("default-node");
        defaultNode.setActive(true);
        NodeInfo remoteNode = new NodeInfo();
        remoteNode.setNodeId("remote-node");
        remoteNode.setActive(true);
        NodeInfo defaultLocalNode = new NodeInfo();
        defaultLocalNode.setNodeId("fresh-default-local");
        defaultLocalNode.setName("Default Local");
        defaultLocalNode.setIp("http://mediata-default-node.local:18083");
        defaultLocalNode.setActive(true);
        stratifProject.setNodeIds(List.of("default-node", "remote-node", "fresh-default-local"));
        when(nodeRepository.findAll()).thenReturn(List.of(defaultNode, remoteNode, defaultLocalNode));

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        verify(projectRepository).save(argThat(project ->
                "STRATIF-AI".equals(project.getName())
                        && project.getNodeIds().equals(List.of("remote-node"))));
    }

    @Test
    void initDatabase_createsRoles_whenNotExist() throws Exception {
        when(roleRepository.findByName(anyString())).thenReturn(null);
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);
        when(projectRepository.findByName(anyString())).thenReturn(new Project());

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, times(2)).save(roleCaptor.capture());

        assertThat(roleCaptor.getAllValues())
                .extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void initDatabase_createsAdminUser_whenNotExists() throws Exception {
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(adminRole);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(new Role());
        when(userRepository.findByUsername("admin")).thenReturn(null);
        when(projectRepository.findByName(anyString())).thenReturn(new Project());
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(kerberosService.getRealm()).thenReturn("REALM");
        when(kerberosService.getPrincipalName(anyString(), anyString())).thenReturn("admin@REALM");

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo("admin");
        assertThat(savedUser.getEmail()).isEqualTo("admin@mediata.local");
        verify(kerberosService).createPrincipal("admin@REALM", "admin");
    }

    @Test
    void initDatabase_handlesKerberosException_gracefully() throws Exception {
        Role adminRole = new Role();
        adminRole.setName("ROLE_ADMIN");

        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(adminRole);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(new Role());
        when(userRepository.findByUsername("admin")).thenReturn(null);
        when(projectRepository.findByName(anyString())).thenReturn(new Project());
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(kerberosService.getRealm()).thenReturn("REALM");
        when(kerberosService.getPrincipalName(anyString(), anyString())).thenReturn("admin@REALM");
        doThrow(new KrbException("Kerberos error")).when(kerberosService).createPrincipal(anyString(), anyString());

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository, nodeRepository);

        runner.run();

        verify(userRepository).save(any(User.class));
    }
}
