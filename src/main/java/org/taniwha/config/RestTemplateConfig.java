package org.taniwha.config;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class RestTemplateConfig {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration NODE_PROXY_READ_TIMEOUT = Duration.ofMinutes(6);
    private static final int MAX_TOTAL_CONNECTIONS = 64;
    private static final int MAX_CONNECTIONS_PER_ROUTE = 16;

    private final RestTemplate restTemplate;
    private final RestTemplate nodeProxyRestTemplate;

    public RestTemplateConfig() {
        this.restTemplate = createRestTemplate(REQUEST_TIMEOUT);
        this.nodeProxyRestTemplate = createRestTemplate(NODE_PROXY_READ_TIMEOUT);
    }

    public RestTemplate getRestTemplate() {
        return restTemplate;
    }

    public RestTemplate getNodeProxyRestTemplate() {
        return nodeProxyRestTemplate;
    }

    private RestTemplate createRestTemplate(Duration readTimeout) {
        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnTotal(MAX_TOTAL_CONNECTIONS)
                        .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
                        .build();
        CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setConnectionManager(connectionManager)
                .build();
        HttpComponentsClientHttpRequestFactory requestFactory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        requestFactory.setConnectTimeout(REQUEST_TIMEOUT);
        requestFactory.setConnectionRequestTimeout(REQUEST_TIMEOUT);
        requestFactory.setReadTimeout(readTimeout);
        return new RestTemplate(requestFactory);
    }
}
