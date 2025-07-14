package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.taniwha.dto.LoginResponseDTO;
import org.taniwha.dto.RegisterRequestDTO;
import org.taniwha.model.Role;
import org.taniwha.model.User;
import org.taniwha.repository.RoleRepository;
import org.taniwha.repository.UserRepository;
import org.taniwha.util.JwtTokenUtil;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

// All user related functionality goes here
@Service
public class UserService implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final KerberosService kerberosService;
    private final JwtTokenUtil jwtTokenUtil;
    private final AuthenticationManager authenticationManager;

    @Autowired
    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       @Lazy KerberosService kerberosService,
                       JwtTokenUtil jwtTokenUtil,
                       @Lazy AuthenticationManager authenticationManager) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.kerberosService = kerberosService;
        this.jwtTokenUtil = jwtTokenUtil;
        this.authenticationManager = authenticationManager;
    }

    public void registerUser(RegisterRequestDTO registerRequest, String token) throws KrbException {
        List<Role> roles;
        if (token != null && token.startsWith("Bearer ")) {
            String authUsername = jwtTokenUtil.getUsernameFromToken(token.substring(7));
            User authUser = findByUsername(authUsername);
            List<Role> authUserRoles = authUser.getRoles();
            boolean isAdmin = authUserRoles.stream().anyMatch(role -> "ROLE_ADMIN".equals(role.getName()));
            if (!isAdmin) {
                Role userRole = findRoleByName("ROLE_USER");
                roles = Collections.singletonList(userRole);
            } else {
                roles = registerRequest.getRoles().stream()
                        .map(this::findRoleByName)
                        .collect(Collectors.toList());
            }
        } else {
            Role userRole = findRoleByName("ROLE_USER");
            roles = Collections.singletonList(userRole);
        }
        String encodedPassword = passwordEncoder.encode(registerRequest.getPassword());
        User newUser = new User(
                null,
                registerRequest.getUsername(),
                encodedPassword,
                registerRequest.getEmail(),
                roles,
                Collections.emptyList()
        );
        userRepository.save(newUser);
        kerberosService.createPrincipal(kerberosService.getPrincipalName(newUser.getUsername(), kerberosService.getRealm()), encodedPassword);
        kerberosService.createKeytab(kerberosService.getPrincipalName(newUser.getUsername(), kerberosService.getRealm()));
    }

    public LoginResponseDTO loginUser(String username, String password) {
        logger.debug("Authenticating user: {}", username);
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        final UserDetails userDetails = loadUserByUsername(username);
        final String token = jwtTokenUtil.generateToken(userDetails.getUsername());
        String tgtTicket = kerberosService.requestTgt(kerberosService.getPrincipalName(username, kerberosService.getRealm()), findByUsername(username).getPassword());

        if (tgtTicket == null)
            logger.error("Kerberos TGT request failed for user: {}", username);

        return new LoginResponseDTO(token, tgtTicket);
    }

    public Role findRoleByName(String roleName) {
        return roleRepository.findByName(roleName);
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username);
        if (user == null)
            throw new UsernameNotFoundException("User not found");
        List<GrantedAuthority> authorities = user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority(role.getName()))
                .collect(Collectors.toList());

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password(user.getPassword())
                .authorities(authorities)
                .build();
    }

    public boolean isUsernameTaken(String username) {
        return userRepository.findByUsername(username) != null;
    }

    public boolean userHasAccessToNode(String username, String nodeId) {
        User user = userRepository.findByUsername(username);
        return user != null && user.getNodeIds().stream().anyMatch(nodeInfo -> nodeInfo != null && nodeInfo.getNodeId().equals(nodeId));
    }
}
