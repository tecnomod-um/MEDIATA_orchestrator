package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeMetadata;
import org.taniwha.repository.NodeRepository;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

// Functionality related to users accessing a node securely
@Service
public class NodeAccessService {

    private static final Logger logger = LoggerFactory.getLogger(NodeAccessService.class);
    private static final String ERROR_KEY = "error";
    private static final String NODE_NOT_FOUND_MESSAGE = "Node not found";
    private static final String SERVICE_TICKET_ERROR_PREFIX = "Failed to request Kerberos service ticket for node ";
    private static final String NODE_NOT_TRUSTED_MESSAGE_PREFIX = "HTTP access is not enabled for node ";
    private final NodeRepository nodeRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private final KerberosService kerberosService;
    private final UserService userService;
    private final RestTemplateConfig restTemplateConfig;
    private final TrustedNodeProxyConfig trustedNodeProxyConfig;
    private final TrustedNodeSignatureService trustedNodeSignatureService;

    private final String realm;


    @Autowired
    public NodeAccessService(NodeRepository nodeRepository, JwtTokenUtil jwtTokenUtil,
                             KerberosService kerberosService, UserService userService, RestTemplateConfig restTemplateConfig,
                             TrustedNodeProxyConfig trustedNodeProxyConfig,
                             TrustedNodeSignatureService trustedNodeSignatureService,
                             @Value("${kerberos.realm}") String realm) {
        this.nodeRepository = nodeRepository;
        this.jwtTokenUtil = jwtTokenUtil;
        this.kerberosService = kerberosService;
        this.userService = userService;
        this.restTemplateConfig = restTemplateConfig;
        this.trustedNodeProxyConfig = trustedNodeProxyConfig;
        this.trustedNodeSignatureService = trustedNodeSignatureService;
        this.realm = realm;
    }

    public boolean checkUserAccess(String nodeId, String userToken) {
        String username = jwtTokenUtil.getUsernameFromToken(userToken);
        return userService.userHasAccessToNode(username, nodeId);
    }

    private NodeInfo getNodeInfo(String nodeId) {
        return nodeRepository.findById(nodeId).orElse(null);
    }

    public NodeMetadata getMetadata(String nodeId) {
        NodeInfo nodeInfo = getNodeInfo(nodeId);
        if (nodeInfo == null) {
            logger.warn("Node not found: {}", nodeId);
            return null;
        }

        String baseUrl = resolveCentralNodeBaseUrl(nodeInfo);
        if (baseUrl == null) {
            logger.warn("Skipping metadata request for untrusted HTTP node {}", nodeId);
            return null;
        }

        String url = baseUrl + "/taniwha/node/metadata";
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();

        try {
            ResponseEntity<NodeMetadata> response = restTemplate.getForEntity(url, NodeMetadata.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }

            logger.warn("Unexpected status fetching node metadata for nodeId {} from {}: {}",
                    nodeId, url, response.getStatusCode());
            return null;

        } catch (HttpClientErrorException.NotFound e) {
            logger.debug("No metadata found for nodeId {} at {}", nodeId, url);
            return null;
        } catch (HttpClientErrorException e) {
            logger.warn("Client error fetching node metadata for nodeId {} from {}: {} {}", nodeId, url, e.getStatusCode(), e.getStatusText());
            return null;

        } catch (HttpServerErrorException e) {
            logger.error("Server error fetching node metadata for nodeId {} from {}: {} {}", nodeId, url, e.getStatusCode(), e.getStatusText());
            return null;

        } catch (ResourceAccessException e) {
            logger.error("I/O error fetching node metadata for nodeId {} from {}", nodeId, url, e);
            return null;
        } catch (RestClientException e) {
            logger.error("Unexpected error fetching node metadata for nodeId {} from {}",
                    nodeId, url, e);
            return null;
        }
    }

    public boolean isFairDataPointEnabled(String nodeId) {
        NodeInfo nodeInfo = getNodeInfo(nodeId);
        if (nodeInfo == null) {
            logger.warn("Node not found while probing FAIR Data Point status: {}", nodeId);
            return false;
        }

        String baseUrl = resolveCentralNodeBaseUrl(nodeInfo);
        if (baseUrl == null) {
            logger.warn("Skipping FAIR Data Point probe for untrusted HTTP node {}", nodeId);
            return false;
        }

        String url = baseUrl + "/taniwha/fdp";
        RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.valueOf("text/turtle")));
        HttpEntity<Void> request = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    request,
                    String.class
            );
            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpStatusCodeException e) {
            logger.debug("FAIR Data Point probe for nodeId {} at {} returned status {}",
                    nodeId, url, e.getStatusCode());
            return false;
        } catch (ResourceAccessException e) {
            logger.warn("I/O error probing FAIR Data Point for nodeId {} from {}", nodeId, url, e);
            return false;
        } catch (RestClientException e) {
            logger.warn("Unexpected error probing FAIR Data Point for nodeId {} from {}",
                    nodeId, url, e);
            return false;
        }
    }

    public Map<String, Object> getServiceToken(String nodeId, String userTgtToken) {
        NodeInfo nodeInfo = getNodeInfo(nodeId);
        Map<String, Object> response = new HashMap<>();

        if (nodeInfo == null) {
            logger.error("Tried fetching node that doesnt exist");
            response.put(ERROR_KEY, NODE_NOT_FOUND_MESSAGE);
        } else {
            String baseUrl = resolveCentralNodeBaseUrl(nodeInfo);
            if (baseUrl == null) {
                response.put(ERROR_KEY, NODE_NOT_TRUSTED_MESSAGE_PREFIX + nodeInfo.getName());
                return response;
            }

            // Access the node's principal
            String serviceToken;
            try {
                serviceToken = kerberosService.requestSgt(userTgtToken, kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
            } catch (IOException | KrbException e) {
                logger.error("Error requesting the Sgt token", e);
                response.put(ERROR_KEY, SERVICE_TICKET_ERROR_PREFIX + nodeInfo.getName());
                return response;
            }

            if (serviceToken != null) {
                boolean success = sendTokenToNode(nodeInfo, baseUrl, serviceToken);
                if (success) {
                    response.put("token", serviceToken);
                } else {
                    response.put(ERROR_KEY, "Failed to connect to node " + nodeInfo.getName());
                }
            } else {
                response.put(ERROR_KEY, "Failed to generate token. Node can't be reached");
            }
        }
        return response;
    }

    private boolean sendTokenToNode(NodeInfo nodeInfo, String baseUrl, String sgtToken) {
        String url = baseUrl + "/taniwha/node/authorize";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("token", sgtToken);
        byte[] serializedBody = ("{\"token\":\"" + sgtToken.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        URI targetUri = URI.create(url);
        TrustedNodeSignatureService.SignedRequestContext signedRequestContext =
                trustedNodeSignatureService.signRequestIfNeeded(
                                HttpMethod.POST,
                                targetUri,
                                headers,
                                serializedBody,
                                trustedNodeProxyConfig.resolveSharedSecret(nodeInfo)
                        )
                        .orElse(null);
        HttpEntity<byte[]> request = new HttpEntity<>(serializedBody, headers);
        try {
            logger.debug("Sending token to node URL: {}", url);
            RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
            ResponseEntity<byte[]> response = restTemplate.exchange(targetUri, HttpMethod.POST, request, byte[].class);
            if (!trustedNodeSignatureService.verifyResponseIfNeeded(
                    signedRequestContext, response.getStatusCode(), response.getHeaders(), response.getBody())) {
                logger.warn("Trusted proxy signature verification failed for authorize response from {}", url);
                return false;
            }
            logger.debug("Sending token to node URL: {} with response: {}", url, response.getStatusCode());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            logger.error("Error connecting to node", e);
            return false;
        }
    }

    private String resolveCentralNodeBaseUrl(NodeInfo nodeInfo) {
        if (nodeInfo == null) {
            return null;
        }

        return trustedNodeProxyConfig.resolveUpstreamBaseUrl(nodeInfo)
                .orElseGet(() -> {
                    String serviceUrl = nodeInfo.getServiceUrl();
                    if (serviceUrl != null && serviceUrl.toLowerCase().startsWith("https://")) {
                        return serviceUrl;
                    }
                    return null;
                });
    }
}
