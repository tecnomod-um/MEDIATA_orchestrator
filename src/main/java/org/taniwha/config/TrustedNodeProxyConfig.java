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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

// Sets up trusted nodes for tunneling http connexions
@Component
public class TrustedNodeProxyConfig {

    private static final Logger logger = LoggerFactory.getLogger(TrustedNodeProxyConfig.class);
    private static final String COMMENT_PREFIX = "#";
    private static final String DEFAULT_PROXY_BASE_PREFIX = "/nodes/proxy/";

    private final Path configPath;

    private volatile Map<String, TrustedNodeRoute> trustedRoutes = Collections.emptyMap();
    private volatile long lastModifiedMillis = Long.MIN_VALUE;

    @Value("${trusted.proxy.allow-http-upstream:false}")
    private boolean allowHttpUpstream;

    @Value("${trusted.proxy.allowed-upstream-hosts:localhost,127.0.0.1,host.docker.internal}")
    private String allowedUpstreamHosts;

    @org.springframework.beans.factory.annotation.Autowired
    public TrustedNodeProxyConfig(@Value("${trusted.servers.config.path:trusted-servers.config}") String configPathValue) {
        this(configPathValue, "localhost,127.0.0.1,host.docker.internal");
    }

    public TrustedNodeProxyConfig(String configPathValue, String allowedUpstreamHosts) {
        this.configPath = Paths.get(configPathValue);
        this.allowedUpstreamHosts = allowedUpstreamHosts;
        this.allowHttpUpstream = true;
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
            logger.warn("Ignoring invalid trusted node entry");
            return null;
        }

        URI publicUri = parseUri(parts[0].trim());
        if (publicUri == null || !"http".equalsIgnoreCase(publicUri.getScheme())) {
            logger.warn("Ignoring trusted node entry without an HTTP public origin");
            return null;
        }

        String publicOrigin = toOrigin(publicUri);
        String upstreamBaseUrl = parts.length > 1 && !parts[1].isBlank()
                ? parts[1].trim()
                : publicOrigin;
        URI upstreamUri = parseUri(upstreamBaseUrl);
        if (upstreamUri == null || (!"https".equalsIgnoreCase(upstreamUri.getScheme())
                && !(allowHttpUpstream && "http".equalsIgnoreCase(upstreamUri.getScheme())))
                || upstreamUri.getUserInfo() != null || upstreamUri.getFragment() != null
                || !allowedUpstreamHosts().contains(upstreamUri.getHost().toLowerCase())) {
            logger.warn("Ignoring trusted node entry with invalid upstream URL");
            return null;
        }

        String sharedSecret = parts.length > 2 ? parts[2].trim() : null;
        if (sharedSecret == null || sharedSecret.length() < 32) {
            logger.warn("Ignoring trusted node entry without a strong shared secret: {}", publicOrigin);
            return null;
        }

        return new TrustedNodeRoute(publicOrigin, upstreamBaseUrl.replaceAll("/+$", ""), sharedSecret);
    }

    private Set<String> allowedUpstreamHosts() {
        String configured = allowedUpstreamHosts;
        if (configured == null || configured.isBlank()) {
            configured = "localhost,127.0.0.1,host.docker.internal";
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(host -> !host.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private URI parseUri(String value) {
        try {
            URI uri = new URI(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                logger.warn("Ignoring invalid trusted node entry without scheme/host");
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            logger.warn("Ignoring invalid trusted node entry ({})", e.getMessage());
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
