package org.taniwha.config;

import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.client.ResourceAccessException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SpringExtension.class)
class RestTemplateConfigTest {

    @Test
    void bothClientsRejectAnUntrustedTlsCertificate(@TempDir Path temporary) throws Exception {
        Path keyStore = temporary.resolve("server.p12");
        String password = "local-test-only";
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process keytool = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                "-storepass", password, "-keypass", password, "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve("keytool.log").toFile()).start();
        if (!keytool.waitFor(30, TimeUnit.SECONDS)) {
            keytool.destroyForcibly();
            throw new IllegalStateException("Timed out generating the test certificate");
        }
        assertThat(keytool.exitValue()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(keyStore)) {
            keys.load(stream, password.toCharArray());
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password.toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        AtomicBoolean requestReachedApplication = new AtomicBoolean();
        server.createContext("/", exchange -> {
            requestReachedApplication.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            RestTemplateConfig config = new RestTemplateConfig();
            String url = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
            for (RestTemplate client : new RestTemplate[]{config.getRestTemplate(), config.getNodeProxyRestTemplate()}) {
                assertThatThrownBy(() -> client.getForObject(url, String.class))
                        .isInstanceOf(ResourceAccessException.class)
                        .hasCauseInstanceOf(SSLHandshakeException.class);
            }
            assertThat(requestReachedApplication).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void restTemplate_isConfiguredWithPooledHttpClient() throws Exception {

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

    @Test
    void nodeProxyRestTemplateUsesTheSameSecureClientConfiguration() {
        RestTemplateConfig config = new RestTemplateConfig();
        RestTemplate regularRestTemplate = config.getRestTemplate();
        RestTemplate restTemplate = config.getNodeProxyRestTemplate();

        assertThat(restTemplate.getRequestFactory())
                .isInstanceOf(HttpComponentsClientHttpRequestFactory.class);
        assertThat(config.getNodeProxyRestTemplate()).isSameAs(restTemplate);
        assertThat(config.getRestTemplate()).isSameAs(regularRestTemplate);
    }

    @Test
    void restTemplatesUsePooledConnectionManagers() throws Exception {
        RestTemplateConfig config = new RestTemplateConfig();

        assertThat(connectionManager(config.getRestTemplate()))
                .isInstanceOf(PoolingHttpClientConnectionManager.class);
        assertThat(connectionManager(config.getNodeProxyRestTemplate()))
                .isInstanceOf(PoolingHttpClientConnectionManager.class);
    }

    private static Object connectionManager(RestTemplate restTemplate) throws Exception {
        HttpComponentsClientHttpRequestFactory rf =
                (HttpComponentsClientHttpRequestFactory) restTemplate.getRequestFactory();
        Field httpClientFld = HttpComponentsClientHttpRequestFactory.class
                .getDeclaredField("httpClient");
        httpClientFld.setAccessible(true);
        Object httpClient = httpClientFld.get(rf);
        return invokeIfPresent(httpClient, "getConnectionManager")
                .orElseGet(() -> getFieldImplementing(httpClient, HttpClientConnectionManager.class));
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
