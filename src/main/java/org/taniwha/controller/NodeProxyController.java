package org.taniwha.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerMapping;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeService;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.service.TrustedNodeSignatureService;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;

@RestController
public class NodeProxyController {

    private static final Logger logger = LoggerFactory.getLogger(NodeProxyController.class);
    private static final String NODE_PROXY_HEADER = "X-Node-Proxy";
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "host",
            "transfer-encoding"
    );
    private static final String ACCESS_CONTROL_HEADER_PREFIX = "access-control-";

    private final NodeService nodeService;
    private final TrustedNodeProxyConfig trustedNodeProxyConfig;
    private final TrustedNodeSignatureService trustedNodeSignatureService;
    private final RestTemplateConfig restTemplateConfig;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public NodeProxyController(NodeService nodeService,
                               TrustedNodeProxyConfig trustedNodeProxyConfig,
                               TrustedNodeSignatureService trustedNodeSignatureService,
                               RestTemplateConfig restTemplateConfig) {
        this.nodeService = nodeService;
        this.trustedNodeProxyConfig = trustedNodeProxyConfig;
        this.trustedNodeSignatureService = trustedNodeSignatureService;
        this.restTemplateConfig = restTemplateConfig;
    }

    @RequestMapping("/nodes/proxy/{nodeId}/**")
    public ResponseEntity<byte[]> proxyNodeRequest(@PathVariable String nodeId, HttpServletRequest request) throws IOException {
        NodeInfo nodeInfo = nodeService.findNodeById(nodeId);
        if (nodeInfo == null) {
            return proxiedResponse(HttpStatus.NOT_FOUND, "Node not found".getBytes(), new HttpHeaders());
        }

        Optional<TrustedNodeProxyConfig.TrustedNodeRoute> route = trustedNodeProxyConfig.resolveRoute(nodeInfo);
        if (route.isEmpty()) {
            return proxiedResponse(HttpStatus.FORBIDDEN, "Proxying is not enabled for this node".getBytes(), new HttpHeaders());
        }

        String downstreamPath = extractDownstreamPath(request);
        String targetUrl = buildTargetUrl(route.get().upstreamBaseUrl(), downstreamPath, request.getQueryString());
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpHeaders headers = buildForwardHeaders(request, downstreamPath);
        byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
        URI targetUri = URI.create(targetUrl);
        TrustedNodeSignatureService.SignedRequestContext signedRequestContext =
                trustedNodeSignatureService.signRequestIfNeeded(
                                method,
                                targetUri,
                                headers,
                                body,
                                Optional.ofNullable(route.get().sharedSecret())
                        )
                        .orElse(null);

        HttpEntity<byte[]> entity = new HttpEntity<>(body.length == 0 ? null : body, headers);
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(targetUri, method, entity, byte[].class);
            if (!trustedNodeSignatureService.verifyResponseIfNeeded(
                    signedRequestContext, response.getStatusCode(), response.getHeaders(), response.getBody())) {
                logger.warn("Trusted proxy signature verification failed for node {} at {}", nodeId, targetUrl);
                return proxiedResponse(HttpStatus.BAD_GATEWAY, "Invalid proxied node response signature".getBytes(), new HttpHeaders());
            }
            return proxiedResponse(response.getStatusCode(), response.getBody(), response.getHeaders());
        } catch (HttpStatusCodeException e) {
            if (!trustedNodeSignatureService.verifyResponseIfNeeded(
                    signedRequestContext, e.getStatusCode(), e.getResponseHeaders(), e.getResponseBodyAsByteArray())) {
                logger.warn("Trusted proxy signature verification failed for error response from node {} at {}", nodeId, targetUrl);
                return proxiedResponse(HttpStatus.BAD_GATEWAY, "Invalid proxied node response signature".getBytes(), new HttpHeaders());
            }
            return proxiedResponse(e.getStatusCode(), e.getResponseBodyAsByteArray(), e.getResponseHeaders());
        } catch (ResourceAccessException e) {
            logger.error("I/O error proxying request to node {} at {}", nodeId, targetUrl, e);
            return proxiedResponse(HttpStatus.BAD_GATEWAY, "Failed to reach proxied node".getBytes(), new HttpHeaders());
        } catch (RestClientException e) {
            logger.error("Unexpected error proxying request to node {} at {}", nodeId, targetUrl, e);
            return proxiedResponse(HttpStatus.BAD_GATEWAY, "Failed to proxy request to node".getBytes(), new HttpHeaders());
        }
    }

    private String extractDownstreamPath(HttpServletRequest request) {
        String pathWithinMapping = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String extracted = pathMatcher.extractPathWithinPattern(bestMatchPattern, pathWithinMapping);
        return extracted == null ? "" : extracted;
    }

    private String buildTargetUrl(String baseUrl, String downstreamPath, String queryString) {
        String normalizedBase = baseUrl.replaceAll("/+$", "");
        String normalizedPath = downstreamPath.startsWith("/") ? downstreamPath : "/" + downstreamPath;
        if (queryString == null || queryString.isBlank()) {
            return normalizedBase + normalizedPath;
        }
        return normalizedBase + normalizedPath + "?" + queryString;
    }

    private HttpHeaders buildForwardHeaders(HttpServletRequest request, String downstreamPath) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();

        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String normalizedHeader = headerName.toLowerCase();
            if (HOP_BY_HOP_HEADERS.contains(normalizedHeader)
                    || "authorization".equals(normalizedHeader)
                    || "x-node-authorization".equals(normalizedHeader)) {
                continue;
            }

            Enumeration<String> values = request.getHeaders(headerName);
            while (values.hasMoreElements()) {
                headers.add(headerName, values.nextElement());
            }
        }

        String forwardedAuthorization = request.getHeader("X-Node-Authorization");
        if (forwardedAuthorization == null && "taniwha/node/validate".equals(downstreamPath)) {
            forwardedAuthorization = request.getHeader("Authorization");
        }
        if (forwardedAuthorization != null && !forwardedAuthorization.isBlank()) {
            headers.set("Authorization", forwardedAuthorization);
        }

        return headers;
    }

    private ResponseEntity<byte[]> proxiedResponse(HttpStatusCode status, byte[] body, HttpHeaders upstreamHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (upstreamHeaders != null) {
            upstreamHeaders.forEach((name, values) -> {
                String normalizedName = name.toLowerCase();
                if (!HOP_BY_HOP_HEADERS.contains(normalizedName)
                        && !normalizedName.startsWith(ACCESS_CONTROL_HEADER_PREFIX)) {
                    headers.put(name, values);
                }
            });
        }
        headers.set(NODE_PROXY_HEADER, "true");
        byte[] responseBody = body == null ? new byte[0] : body;
        return new ResponseEntity<>(responseBody, headers, status);
    }
}
