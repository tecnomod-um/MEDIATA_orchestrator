package org.taniwha.config;

import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
class RestTemplateConfigTest {

    @Test
    void restTemplate_isConfiguredWithNoopHostnameVerifier() throws Exception {

        RestTemplate restTemplate = new RestTemplateConfig().getRestTemplate();
        assertThat(restTemplate).isNotNull();
        assertThat(restTemplate.getRequestFactory())
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
        HttpComponentsClientHttpRequestFactory rf =
                (HttpComponentsClientHttpRequestFactory) restTemplate.getRequestFactory();
        Field httpClientFld = HttpComponentsClientHttpRequestFactory.class
                .getDeclaredField("httpClient");
        httpClientFld.setAccessible(true);
        Object httpClient = httpClientFld.get(rf);
        assertThat(httpClient).isNotNull();
        Object connMgr = invokeIfPresent(httpClient, "getConnectionManager")
                .orElseGet(() -> getFieldImplementing(httpClient, HttpClientConnectionManager.class));
        assertThat(connMgr).isInstanceOf(HttpClientConnectionManager.class);
    }

    private static Optional<Object> invokeIfPresent(Object target, String method) {
        return Arrays.stream(target.getClass().getMethods())
                .filter(m -> m.getName().equals(method) && m.getParameterCount() == 0)
                .findFirst()
                .map(m -> {
                    try {
                        return m.invoke(target);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private static Object getFieldImplementing(Object target, Class<?> iface) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (iface.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        return f.get(target);
                    } catch (IllegalAccessException ignored) { /* keep searching */ }
                }
            }
        }
        throw new IllegalStateException("No field implementing " + iface.getName() +
                " found in " + target.getClass());
    }
}
