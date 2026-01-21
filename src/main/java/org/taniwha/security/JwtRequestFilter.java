package org.taniwha.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.taniwha.service.UserService;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {

    private static final Logger filterLogger = LoggerFactory.getLogger(JwtRequestFilter.class);
    private final JwtTokenUtil jwtTokenUtil;
    private final ApplicationContext applicationContext;
    
    @Value("${jwt.filter.enabled:true}")
    private boolean jwtFilterEnabled;

    public JwtRequestFilter(JwtTokenUtil jwtTokenUtil, ApplicationContext applicationContext) {
        this.jwtTokenUtil = jwtTokenUtil;
        this.applicationContext = applicationContext;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {

        // Skip JWT authentication if disabled (e.g., in tests)
        if (!jwtFilterEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // Allow requests to log in and register endpoints to bypass the filter
        if (isExemptedEndpoint(request)) {
            chain.doFilter(request, response);
            return;
        }

        String jwtToken = extractJwtToken(request, response);
        if (jwtToken == null) return;

        String username = validateTokenAndExtractUsername(jwtToken, response);
        if (username == null) return;

        if (!authenticateUser(request, response, jwtToken, username)) return;

        chain.doFilter(request, response);
    }

    private boolean isExemptedEndpoint(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        return requestURI.endsWith("/api/user/login") 
            || requestURI.endsWith("/api/user/register") 
            || requestURI.contains("/api/error");
    }

    private String extractJwtToken(HttpServletRequest request, HttpServletResponse response) throws IOException {
        final String requestTokenHeader = request.getHeader("Authorization");

        if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer "))
            return requestTokenHeader.substring(7);
        else {
            filterLogger.warn("JWT Token does not begin with Bearer String");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT Token does not begin with Bearer String");
            return null;
        }
    }

    private String validateTokenAndExtractUsername(String jwtToken, HttpServletResponse response) throws IOException {
        try {
            return jwtTokenUtil.getUsernameFromToken(jwtToken);
        } catch (IllegalArgumentException e) {
            filterLogger.warn("Unable to get JWT Token: {}", e.getMessage());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unable to get JWT Token");
        } catch (ExpiredJwtException e) {
            filterLogger.warn("JWT Token has expired");
            response.setHeader("X-Token-Expired", "true");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "JWT Token has expired");
        } catch (SignatureException e) {
            filterLogger.warn("Invalid JWT Signature");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid JWT Signature");
        } catch (MalformedJwtException e) {
            filterLogger.warn("Malformed JWT Token");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Malformed JWT Token");
        }
        return null;
    }

    private boolean authenticateUser(HttpServletRequest request, HttpServletResponse response, String jwtToken, String username)
            throws IOException {
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserService userService = applicationContext.getBean(UserService.class);
            try {
                UserDetails userDetails = userService.loadUserByUsername(username);
                if (jwtTokenUtil.validateToken(jwtToken, userDetails.getUsername())) {
                    UsernamePasswordAuthenticationToken authenticationToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authenticationToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authenticationToken);
                    return true;
                } else {
                    // Token validation failed - don't authenticate but don't send error
                    // (let Spring Security handle it)
                    return true;
                }
            } catch (UsernameNotFoundException e) {
                filterLogger.warn("User not found: {}", username);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "User not found");
                return false;
            }
        }
        return true;
    }
}
