package org.taniwha.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtTokenUtil {

    private static final String NODE_SYNC_ISSUER = "mediata-node";
    private static final String NODE_SYNC_AUDIENCE = "mediata-orchestrator";

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private long expiration;

    private SecretKey getSigningKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String subject) {
        Map<String, Object> claims = new HashMap<>();
        return doGenerateToken(claims, subject);
    }

    private String doGenerateToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration * 1000))
                .signWith(getSigningKey())
                .compact();
    }

    public String getUsernameFromToken(String token) {
        return getAllClaimsFromToken(token).getSubject();
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token, String subject) {
        final String extractedSubject = getUsernameFromToken(token);
        return extractedSubject.equals(subject) && !isTokenExpired(token);
    }

    public boolean validateNodeSyncToken(String token,
                                         String nodeId,
                                         String endpoint,
                                         String publicKeyBase64) {
        try {
            if (nodeId == null || endpoint == null || publicKeyBase64 == null) {
                return false;
            }
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)));
            Claims claims = Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return "node_sync".equals(claims.get("token_use", String.class))
                    && NODE_SYNC_ISSUER.equals(claims.getIssuer())
                    && claims.getAudience() != null
                    && claims.getAudience().contains(NODE_SYNC_AUDIENCE)
                    && nodeId.equals(claims.getSubject())
                    && nodeId.equals(claims.get("node_id", String.class))
                    && endpoint.equals(claims.get("sync_endpoint", String.class))
                    && claims.getExpiration() != null
                    && !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        final Date tokenExpiration = getAllClaimsFromToken(token).getExpiration();
        return tokenExpiration.before(new Date());
    }
}
