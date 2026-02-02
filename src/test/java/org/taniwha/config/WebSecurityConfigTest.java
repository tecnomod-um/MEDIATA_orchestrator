package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.taniwha.security.JwtRequestFilter;
import org.taniwha.service.UserService;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebSecurityConfigTest {

    @Test
    void beans_areCreatedAndWiredCorrectly() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(JwtRequestFilter.class, () -> mock(JwtRequestFilter.class));
            ctx.registerBean(UserService.class, () -> mock(UserService.class));
            ctx.register(WebSecurityConfig.class);
            ctx.refresh();
            SecurityFilterChain chain = ctx.getBean(SecurityFilterChain.class);
            assertThat(chain).as("securityFilterChain").isNotNull();

            PasswordEncoder encoder = ctx.getBean(PasswordEncoder.class);
            assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);

            AuthenticationManager authManager = ctx.getBean(AuthenticationManager.class);
            assertThat(authManager).as("authenticationManager").isNotNull();

            CorsConfigurationSource source = ctx.getBean(CorsConfigurationSource.class);
            assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
            UrlBasedCorsConfigurationSource urlSrc = (UrlBasedCorsConfigurationSource) source;

            Map<String, CorsConfiguration> configs = urlSrc.getCorsConfigurations();
            CorsConfiguration cfg = configs.get("/**");
            assertThat(cfg).isNotNull();
            assertThat(cfg.getAllowedOrigins())
                    .containsExactlyInAnyOrder("https://semantics.inf.um.es", "http://localhost:3000");
            assertThat(cfg.getAllowedMethods())
                    .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "OPTIONS");
            assertThat(cfg.getAllowedHeaders())
                    .containsExactlyInAnyOrder("Authorization", "Cache-Control", "Content-Type", "Kerberos-TGT");
            assertThat(cfg.getExposedHeaders())
                    .containsExactly("Authorization");
            assertThat(cfg.getAllowCredentials()).isTrue();
            CorsFilter filter = ctx.getBean(CorsFilter.class);
            Field configField = CorsFilter.class.getDeclaredField("configSource");
            configField.setAccessible(true);
            Object wrapped = configField.get(filter);
            assertThat(wrapped).isSameAs(source);
        }
    }
}
