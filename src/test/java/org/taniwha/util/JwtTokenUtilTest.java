package org.taniwha.util;

import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenUtilTest {

    private JwtTokenUtil loadWith(String secret, String expiration) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                ctx,
                "jwt.secret=" + secret,
                "jwt.expiration=" + expiration
        );
        ctx.register(JwtTokenUtil.class);
        ctx.refresh();
        return ctx.getBean(JwtTokenUtil.class);
    }

    @Test
    void generate_and_parse_and_validate_token() {
        // Use exactly 32 characters (256 bits) for HMAC-SHA256
        JwtTokenUtil util = loadWith("12345678901234567890123456789012", "3600");

        String token = util.generateToken("alice");
        assertThat(token).isNotBlank();

        // parsing
        String username = util.getUsernameFromToken(token);
        assertThat(username).isEqualTo("alice");

        // valid for same subject
        assertThat(util.validateToken(token, "alice")).isTrue();

        // invalid for different subject
        assertThat(util.validateToken(token, "bob")).isFalse();
    }

    @Test
    void expired_token_throwsExpiredJwtException() throws InterruptedException {
        // Use exactly 32 characters (256 bits) for HMAC-SHA256
        JwtTokenUtil util = loadWith("abcdefghijklmnopqrstuvwxyz123456", "0");

        String token = util.generateToken("eve");
        Thread.sleep(10);

        assertThatThrownBy(() -> util.validateToken(token, "eve"))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void generateToken_createsValidJWT() {
        JwtTokenUtil util = loadWith("AAAABBBBCCCCDDDDEEEEFFFFGGGGHHHH", "3600");

        String token = util.generateToken("testuser");
        
        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3); // JWT has 3 parts separated by dots
    }

    @Test
    void getUsernameFromToken_extractsCorrectSubject() {
        JwtTokenUtil util = loadWith("11112222333344445555666677778888", "3600");

        String token = util.generateToken("john.doe");
        String extractedUsername = util.getUsernameFromToken(token);
        
        assertThat(extractedUsername).isEqualTo("john.doe");
    }

    @Test
    void validateToken_returnsFalseForExpiredToken() throws InterruptedException {
        JwtTokenUtil util = loadWith("aaaabbbbccccddddeeeeffffgggghhhh", "0");

        String token = util.generateToken("user");
        Thread.sleep(10); // Wait for token to expire

        // Should throw ExpiredJwtException when trying to validate
        assertThatThrownBy(() -> util.validateToken(token, "user"))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void validateToken_returnsFalseForWrongSubject() {
        JwtTokenUtil util = loadWith("WXYZWXYZWXYZWXYZWXYZWXYZWXYZWXYZ", "3600");

        String token = util.generateToken("alice");
        boolean isValid = util.validateToken(token, "bob");
        
        assertThat(isValid).isFalse();
    }

    @Test
    void generateToken_withLongExpiration() {
        JwtTokenUtil util = loadWith("01234567890123456789012345678901", "86400"); // 24 hours

        String token = util.generateToken("longuser");
        
        assertThat(token).isNotBlank();
        boolean isValid = util.validateToken(token, "longuser");
        assertThat(isValid).isTrue();
    }
}
