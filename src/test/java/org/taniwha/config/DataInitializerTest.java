package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;
import org.taniwha.service.KerberosService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private KerberosService kerberosService;

    private DataInitializer dataInitializer;

    @BeforeEach
    void setUp() {
        dataInitializer = new DataInitializer();
        // Setup common Kerberos mocks with lenient stubbing (not all tests need these)
        lenient().when(kerberosService.getRealm()).thenReturn("MEDIATA.LOCAL");
        lenient().when(kerberosService.getPrincipalName(anyString(), anyString())).thenAnswer(
            invocation -> invocation.getArgument(0) + "@" + invocation.getArgument(1)
        );
    }

    @Test
    void testInitDatabase_createsRolesAndDefaultUser() throws Exception {
        // Given: No roles or users exist
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(null).thenReturn(createRole("ROLE_ADMIN"));
        when(roleRepository.findByName("ROLE_USER")).thenReturn(null);
        when(userRepository.findByUsername("admin")).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");

        // When: Initializing database
        CommandLineRunner runner = dataInitializer.initDatabase(roleRepository, userRepository, passwordEncoder, kerberosService);
        runner.run();

        // Then: Roles and default user are created
        verify(roleRepository, times(2)).save(any(Role.class));
        verify(userRepository, times(1)).save(any(User.class));
        // And: Kerberos principal and keytab are created
        verify(kerberosService, times(1)).createPrincipal(eq("admin@MEDIATA.LOCAL"), eq("encodedPassword"));
        verify(kerberosService, times(1)).createKeytab(eq("admin@MEDIATA.LOCAL"));
    }

    @Test
    void testInitDatabase_doesNotCreateDuplicateRoles() throws Exception {
        // Given: Roles already exist
        Role adminRole = createRole("ROLE_ADMIN");
        Role userRole = createRole("ROLE_USER");
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(adminRole);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(userRole);
        when(userRepository.findByUsername("admin")).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");

        // When: Initializing database
        CommandLineRunner runner = dataInitializer.initDatabase(roleRepository, userRepository, passwordEncoder, kerberosService);
        runner.run();

        // Then: Roles are not created again, but user is created
        verify(roleRepository, never()).save(any(Role.class));
        verify(userRepository, times(1)).save(any(User.class));
        // And: Kerberos principal and keytab are created
        verify(kerberosService, times(1)).createPrincipal(anyString(), anyString());
        verify(kerberosService, times(1)).createKeytab(anyString());
    }

    @Test
    void testInitDatabase_doesNotCreateDuplicateDefaultUser() throws Exception {
        // Given: Roles and default user already exist
        Role adminRole = createRole("ROLE_ADMIN");
        Role userRole = createRole("ROLE_USER");
        User existingAdmin = createUser("admin");
        
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(adminRole);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(userRole);
        when(userRepository.findByUsername("admin")).thenReturn(existingAdmin);

        // When: Initializing database
        CommandLineRunner runner = dataInitializer.initDatabase(roleRepository, userRepository, passwordEncoder, kerberosService);
        runner.run();

        // Then: Neither roles nor user are created
        verify(roleRepository, never()).save(any(Role.class));
        verify(userRepository, never()).save(any(User.class));
        // And: No Kerberos operations are performed
        verify(kerberosService, never()).createPrincipal(anyString(), anyString());
        verify(kerberosService, never()).createKeytab(anyString());
    }

    @Test
    void testInitDatabase_encodesDefaultPassword() throws Exception {
        // Given: No default user exists
        Role adminRole = createRole("ROLE_ADMIN");
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(null).thenReturn(adminRole);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(null);
        when(userRepository.findByUsername("admin")).thenReturn(null);
        when(passwordEncoder.encode("admin")).thenReturn("encodedAdminPassword");

        // When: Initializing database
        CommandLineRunner runner = dataInitializer.initDatabase(roleRepository, userRepository, passwordEncoder, kerberosService);
        runner.run();

        // Then: Password is encoded
        verify(passwordEncoder, times(1)).encode("admin");
    }

    @Test
    void testInitDatabase_handlesKerberosFailureGracefully() throws Exception {
        // Given: No default user exists, but Kerberos creation fails
        Role adminRole = createRole("ROLE_ADMIN");
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(null).thenReturn(adminRole);
        when(roleRepository.findByName("ROLE_USER")).thenReturn(null);
        when(userRepository.findByUsername("admin")).thenReturn(null);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        doThrow(new KrbException("Kerberos not available")).when(kerberosService).createPrincipal(anyString(), anyString());

        // When: Initializing database
        CommandLineRunner runner = dataInitializer.initDatabase(roleRepository, userRepository, passwordEncoder, kerberosService);
        runner.run();

        // Then: User is still created in MongoDB despite Kerberos failure
        verify(userRepository, times(1)).save(any(User.class));
        // And: Kerberos principal creation was attempted
        verify(kerberosService, times(1)).createPrincipal(anyString(), anyString());
        // But: Keytab creation is not attempted after principal creation fails
        verify(kerberosService, never()).createKeytab(anyString());
    }

    private Role createRole(String name) {
        Role role = new Role();
        role.setId("role-id");
        role.setName(name);
        return role;
    }

    private User createUser(String username) {
        User user = new User(
            "user-id",
            username,
            "encodedPassword",
            username + "@mediata.local",
            null,
            null
        );
        return user;
    }
}
