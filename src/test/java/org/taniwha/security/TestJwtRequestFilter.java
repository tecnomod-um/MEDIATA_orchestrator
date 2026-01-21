package org.taniwha.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Test implementation of JwtRequestFilter that bypasses JWT authentication.
 * This allows integration tests to use @WithMockUser without JWT tokens.
 */
@TestComponent
@Primary
@Profile("test")
public class TestJwtRequestFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        // In tests, simply pass through without JWT validation
        chain.doFilter(request, response);
    }
}
