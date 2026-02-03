package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.taniwha.model.Project;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;
import org.taniwha.service.KerberosService;

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

    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        dataInitializer = new DataInitializer();
    }

    @Test
    void initDatabase_createsDefaultProject_whenNotExists() throws Exception {
        // Arrange
        when(roleRepository.findByName(anyString())).thenReturn(new Role());
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);
        when(projectRepository.findByName("Default Project")).thenReturn(null);

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository);

        // Act
        runner.run();

        // Assert
        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(projectCaptor.capture());

        Project savedProject = projectCaptor.getValue();
        assertThat(savedProject.getName()).isEqualTo("Default Project");
        assertThat(savedProject.getDescription()).isEqualTo("Default project created on initialization");
        assertThat(savedProject.getBadge()).isEqualTo("default");
    }

    @Test
    void initDatabase_doesNotCreateProject_whenAlreadyExists() throws Exception {
        // Arrange
        when(roleRepository.findByName(anyString())).thenReturn(new Role());
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);

        Project existingProject = new Project();
        existingProject.setName("Default Project");
        when(projectRepository.findByName("Default Project")).thenReturn(existingProject);

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository);

        // Act
        runner.run();

        // Assert
        verify(projectRepository, never()).save(any(Project.class));
    }

    @Test
    void initDatabase_createsRoles_whenNotExist() throws Exception {
        // Arrange
        when(roleRepository.findByName(anyString())).thenReturn(null);
        User existingUser = new User(null, "admin", "password", "email@test.com", null, null);
        when(userRepository.findByUsername(anyString())).thenReturn(existingUser);
        when(projectRepository.findByName(anyString())).thenReturn(new Project());

        CommandLineRunner runner = dataInitializer.initDatabase(
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository);

        // Act
        runner.run();

        // Assert
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(roleRepository, times(2)).save(roleCaptor.capture());

        assertThat(roleCaptor.getAllValues())
                .extracting(Role::getName)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void initDatabase_createsAdminUser_whenNotExists() throws Exception {
        // Arrange
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
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository);

        // Act
        runner.run();

        // Assert
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo("admin");
        assertThat(savedUser.getEmail()).isEqualTo("admin@mediata.local");
        verify(kerberosService).createPrincipal("admin@REALM", "admin");
    }

    @Test
    void initDatabase_handlesKerberosException_gracefully() throws Exception {
        // Arrange
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
                roleRepository, userRepository, passwordEncoder, kerberosService, projectRepository);

        // Act - should not throw exception
        runner.run();

        // Assert - user should still be saved
        verify(userRepository).save(any(User.class));
    }
}
