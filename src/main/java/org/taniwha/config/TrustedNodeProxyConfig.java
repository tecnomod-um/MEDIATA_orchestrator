package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.taniwha.model.NodeInfo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Sets up trusted nodes for tunneling http connexions
@Component
public class TrustedNodeProxyConfig {

    private static final Logger logger = LoggerFactory.getLogger(TrustedNodeProxyConfig.class);
    private static final String COMMENT_PREFIX = "#";
    private static final String DEFAULT_PROXY_BASE_PREFIX = "/nodes/proxy/";

    private final Path configPath;

    private volatile Map<String, TrustedNodeRoute> trustedRoutes = Collections.emptyMap();
    private volatile long lastModifiedMillis = Long.MIN_VALUE;

    public TrustedNodeProxyConfig(@Value("${trusted.servers.config.path:trusted-servers.config}") String configPathValue) {
        this.configPath = Paths.get(configPathValue);
    }

    public boolean requiresProxy(NodeInfo nodeInfo) {
        return resolveRoute(nodeInfo).isPresent();
    }

    public boolean requiresProxy(String serviceUrl) {
        return resolveRoute(serviceUrl).isPresent();
    }

    public String proxyBasePath(String nodeId) {
        return DEFAULT_PROXY_BASE_PREFIX + nodeId;
    }

    public Optional<TrustedNodeRoute> resolveRoute(NodeInfo nodeInfo) {
        return nodeInfo == null ? Optional.empty() : resolveRoute(nodeInfo.getServiceUrl());
    }

    public Optional<TrustedNodeRoute> resolveRoute(String serviceUrl) {
        if (serviceUrl == null || serviceUrl.isBlank()) {
            return Optional.empty();
        }

        URI serviceUri = parseUri(serviceUrl);
        if (serviceUri == null || !"http".equalsIgnoreCase(serviceUri.getScheme())) {
            return Optional.empty();
        }

        reloadIfNeeded();
        return Optional.ofNullable(trustedRoutes.get(toOrigin(serviceUri)));
    }

    public Optional<String> resolveUpstreamBaseUrl(NodeInfo nodeInfo) {
        if (nodeInfo == null) {
            return Optional.empty();
        }

        URI serviceUri = parseUri(nodeInfo.getServiceUrl());
        if (serviceUri == null) {
            return Optional.empty();
        }

        if ("http".equalsIgnoreCase(serviceUri.getScheme())) {
            return resolveRoute(nodeInfo).map(TrustedNodeRoute::upstreamBaseUrl);
        }

        return Optional.of(nodeInfo.getServiceUrl());
    }

    public Optional<String> resolveSharedSecret(NodeInfo nodeInfo) {
        return resolveRoute(nodeInfo)
                .map(TrustedNodeRoute::sharedSecret)
                .filter(secret -> secret != null && !secret.isBlank());
    }

    private synchronized void reloadIfNeeded() {
        try {
            if (!Files.exists(configPath)) {
                if (lastModifiedMillis != -1) {
                    logger.info("Trusted HTTP node config not found at {}. Proxy exception list is empty.",
                            configPath.toAbsolutePath());
                }
                trustedRoutes = Collections.emptyMap();
                lastModifiedMillis = -1;
                return;
            }

            long currentModified = Files.getLastModifiedTime(configPath).toMillis();
            if (currentModified == lastModifiedMillis) {
                return;
            }

            Map<String, TrustedNodeRoute> loadedRoutes = new LinkedHashMap<>();
            List<String> lines = Files.readAllLines(configPath);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) {
                    continue;
                }

                TrustedNodeRoute route = parseRoute(line);
                if (route != null) {
                    loadedRoutes.put(route.publicOrigin(), route);
                }
            }

            trustedRoutes = Collections.unmodifiableMap(loadedRoutes);
            lastModifiedMillis = currentModified;
            logger.info("Loaded {} trusted HTTP node route(s) from {}",
                    trustedRoutes.size(), configPath.toAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to load trusted HTTP node config from {}", configPath.toAbsolutePath(), e);
            trustedRoutes = Collections.emptyMap();
            lastModifiedMillis = Long.MIN_VALUE;
        }
    }

    private TrustedNodeRoute parseRoute(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0 || parts[0].isBlank()) {
            logger.warn("Ignoring invalid trusted node entry: {}", line);
            return null;
        }

        URI publicUri = parseUri(parts[0].trim());
        if (publicUri == null || !"http".equalsIgnoreCase(publicUri.getScheme())) {
            logger.warn("Ignoring trusted node entry without an HTTP public origin: {}", line);
            return null;
        }

        String publicOrigin = toOrigin(publicUri);
        String upstreamBaseUrl = parts.length > 1 && !parts[1].isBlank()
                ? parts[1].trim()
                : publicOrigin;
        URI upstreamUri = parseUri(upstreamBaseUrl);
        if (upstreamUri == null) {
            logger.warn("Ignoring trusted node entry with invalid upstream URL: {}", line);
            return null;
        }

        String sharedSecret = parts.length > 2 ? parts[2].trim() : null;
        if (sharedSecret != null && sharedSecret.isBlank()) {
            sharedSecret = null;
        }

        return new TrustedNodeRoute(publicOrigin, upstreamBaseUrl.replaceAll("/+$", ""), sharedSecret);
    }

    private URI parseUri(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                logger.warn("Ignoring invalid trusted node entry without scheme/host: {}", value);
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            logger.warn("Ignoring invalid trusted node entry: {} ({})", value, e.getMessage());
            return null;
        }
    }

    private String toOrigin(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase() + ":" + port;
    }

    public record TrustedNodeRoute(String publicOrigin, String upstreamBaseUrl, String sharedSecret) {
    }
}
