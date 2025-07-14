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
        JwtTokenUtil util = loadWith("myVerySecretKey", "3600");

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
        JwtTokenUtil util = loadWith("anotherSecret", "0");

        String token = util.generateToken("eve");
        Thread.sleep(10);

        assertThatThrownBy(() -> util.validateToken(token, "eve"))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
