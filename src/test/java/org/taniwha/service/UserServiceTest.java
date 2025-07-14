package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.taniwha.dto.LoginResponseDTO;
import org.taniwha.dto.RegisterRequestDTO;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;
import org.taniwha.util.JwtTokenUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    UserRepository userRepo;
    @Mock
    RoleRepository roleRepo;
    @Mock
    PasswordEncoder pwdEncoder;
    @Mock
    KerberosService krb;
    @Mock
    JwtTokenUtil jwtUtil;
    @Mock
    AuthenticationManager authManager;

    private UserService svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new UserService(userRepo, roleRepo, pwdEncoder, krb, jwtUtil, authManager);
    }

    @Test
    void registerUser_withoutToken_assignsUserRole() throws KrbException {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setUsername("bob");
        req.setPassword("pass");
        req.setEmail("b@e");
        req.setRoles(Collections.singletonList("ROLE_ADMIN"));

        Role userRole = new Role();
        userRole.setName("ROLE_USER");
        when(roleRepo.findByName("ROLE_USER")).thenReturn(userRole);
        when(pwdEncoder.encode("pass")).thenReturn("ENC");
        when(krb.getPrincipalName("bob", null)).thenReturn("bob@null");

        svc.registerUser(req, null);

        ArgumentCaptor<User> capt = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(capt.capture());
        User saved = capt.getValue();
        assertThat(saved.getUsername()).isEqualTo("bob");
        assertThat(saved.getPassword()).isEqualTo("ENC");
        assertThat(saved.getRoles()).extracting(Role::getName).containsExactly("ROLE_USER");

        verify(krb).createPrincipal("bob@null", "ENC");
        verify(krb).createKeytab("bob@null");
    }

    @Test
    void registerUser_withAdminToken_allowsCustomRoles() throws KrbException {
        RegisterRequestDTO req = new RegisterRequestDTO();
        req.setUsername("joe");
        req.setPassword("pw");
        req.setEmail("e@e");
        req.setRoles(Arrays.asList("ROLE_USER", "ROLE_ADMIN"));

        when(jwtUtil.getUsernameFromToken("admintoken")).thenReturn("admin");
        User admin = new User(
                "x", "admin", "pw", "e",
                Collections.singletonList(new Role() {{
                    setName("ROLE_ADMIN");
                }}),
                Collections.emptyList()
        );
        when(userRepo.findByUsername("admin")).thenReturn(admin);

        Role userR = new Role();
        userR.setName("ROLE_USER");
        Role adminR = new Role();
        adminR.setName("ROLE_ADMIN");
        when(roleRepo.findByName("ROLE_USER")).thenReturn(userR);
        when(roleRepo.findByName("ROLE_ADMIN")).thenReturn(adminR);

        when(pwdEncoder.encode("pw")).thenReturn("Epw");
        when(krb.getPrincipalName("joe", null)).thenReturn("joe@null");

        svc.registerUser(req, "Bearer admintoken");

        ArgumentCaptor<User> capt = ArgumentCaptor.forClass(User.class);
        verify(userRepo).save(capt.capture());
        List<String> names = capt.getValue()
                .getRoles()
                .stream()
                .map(Role::getName)
                .collect(Collectors.toList());
        assertThat(names).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void loginUser_success_returnsTokenAndTgt() {
        String username = "joe", password = "pw";
        User stored = new User(
                "x", username, "Epw", "e",
                Collections.emptyList(),
                Collections.emptyList()
        );
        when(userRepo.findByUsername(username)).thenReturn(stored);
        when(jwtUtil.generateToken(username)).thenReturn("JWT");
        when(krb.getPrincipalName(username, null)).thenReturn(username + "@null");
        when(krb.requestTgt(username + "@null", "Epw")).thenReturn("TGT");

        LoginResponseDTO resp = svc.loginUser(username, password);

        verify(authManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThat(resp.getToken()).isEqualTo("JWT");
        assertThat(resp.getTgt()).isEqualTo("TGT");
    }

    @Test
    void loadUserByUsername_foundAndNotFound() {
        User u = new User(
                "1", "alice", "pw", "e",
                Collections.singletonList(new Role() {{
                    setName("ROLE_USER");
                }}),
                Collections.emptyList()
        );
        when(userRepo.findByUsername("alice")).thenReturn(u);

        UserDetails details = svc.loadUserByUsername("alice");
        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_USER");

        when(userRepo.findByUsername("nope")).thenReturn(null);
        assertThatThrownBy(() -> svc.loadUserByUsername("nope"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void isUsernameTaken_and_userHasAccessToNode() {
        User u = new User(
                "1", "a", "p", "e",
                Collections.emptyList(),
                Collections.singletonList(new org.taniwha.model.NodeInfo() {{
                    setNodeId("n1");
                }})
        );
        when(userRepo.findByUsername("a")).thenReturn(u);
        when(userRepo.findByUsername("b")).thenReturn(null);

        assertThat(svc.isUsernameTaken("a")).isTrue();
        assertThat(svc.isUsernameTaken("b")).isFalse();

        assertThat(svc.userHasAccessToNode("a", "n1")).isTrue();
        assertThat(svc.userHasAccessToNode("a", "x")).isFalse();
        assertThat(svc.userHasAccessToNode("b", "n1")).isFalse();
    }
}
