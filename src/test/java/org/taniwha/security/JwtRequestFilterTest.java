package org.taniwha.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.taniwha.service.UserService;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class JwtRequestFilterTest {

    @Mock
    private JwtTokenUtil jwtTokenUtil;

    @Mock
    private ApplicationContext applicationContext;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    private JwtRequestFilter jwtRequestFilter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        jwtRequestFilter = new JwtRequestFilter(jwtTokenUtil, applicationContext);
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDoFilterInternal_ExemptedLoginEndpoint() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/taniwha/api/user/login");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void testDoFilterInternal_ExemptedRegisterEndpoint() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/taniwha/api/user/register");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void testDoFilterInternal_ExemptedErrorEndpoint() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/taniwha/api/error/logs");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(request, never()).getHeader(anyString());
    }

    @Test
    void testDoFilterInternal_NoAuthorizationHeader() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn(null);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_InvalidBearerToken() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("InvalidToken");

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_ValidToken() throws ServletException, IOException {
        String token = "validToken";
        String username = "testuser";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenReturn(username);
        when(applicationContext.getBean(UserService.class)).thenReturn(userService);
        when(userService.loadUserByUsername(username)).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(username);
        when(userDetails.getAuthorities()).thenReturn(Collections.emptyList());
        when(jwtTokenUtil.validateToken(token, username)).thenReturn(true);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtTokenUtil).getUsernameFromToken(token);
        verify(jwtTokenUtil).validateToken(token, username);
    }

    @Test
    void testDoFilterInternal_ExpiredToken() throws ServletException, IOException {
        String token = "expiredToken";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenThrow(new ExpiredJwtException(null, null, "Token expired"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(response).setHeader("X-Token-Expired", "true");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_MalformedToken() throws ServletException, IOException {
        String token = "malformedToken";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenThrow(new MalformedJwtException("Malformed token"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_InvalidSignature() throws ServletException, IOException {
        String token = "invalidSignatureToken";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenThrow(new SignatureException("Invalid signature"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_IllegalArgumentException() throws ServletException, IOException {
        String token = "invalidToken";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenThrow(new IllegalArgumentException("Invalid token"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void testDoFilterInternal_UserNotFound() throws ServletException, IOException {
        String token = "validToken";
        String username = "nonexistentuser";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenReturn(username);
        when(applicationContext.getBean(UserService.class)).thenReturn(userService);
        when(userService.loadUserByUsername(username)).thenThrow(new UsernameNotFoundException("User not found"));

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(eq(HttpServletResponse.SC_UNAUTHORIZED), anyString());
        // The filter chain is still called after user not found (it doesn't return early in authenticateUser)
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testDoFilterInternal_TokenValidationFails() throws ServletException, IOException {
        String token = "invalidToken";
        String username = "testuser";

        when(request.getRequestURI()).thenReturn("/taniwha/api/nodes");
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtTokenUtil.getUsernameFromToken(token)).thenReturn(username);
        when(applicationContext.getBean(UserService.class)).thenReturn(userService);
        when(userService.loadUserByUsername(username)).thenReturn(userDetails);
        when(userDetails.getUsername()).thenReturn(username);
        when(jwtTokenUtil.validateToken(token, username)).thenReturn(false);

        jwtRequestFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtTokenUtil).validateToken(token, username);
    }
}
