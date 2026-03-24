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
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeMetadata;
import org.taniwha.repository.NodeRepository;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// Functionality related to users accessing a node securely
@Service
public class NodeAccessService {

    private static final Logger logger = LoggerFactory.getLogger(NodeAccessService.class);
    private static final String ERROR_KEY = "error";
    private static final String NODE_NOT_FOUND_MESSAGE = "Node not found";
    private final NodeRepository nodeRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private final KerberosService kerberosService;
    private final UserService userService;
    private final RestTemplateConfig restTemplateConfig;

    private final String realm;


    @Autowired
    public NodeAccessService(NodeRepository nodeRepository, JwtTokenUtil jwtTokenUtil,
                             KerberosService kerberosService, UserService userService, RestTemplateConfig restTemplateConfig,
                             @Value("${kerberos.realm}") String realm) {
        this.nodeRepository = nodeRepository;
        this.jwtTokenUtil = jwtTokenUtil;
        this.kerberosService = kerberosService;
        this.userService = userService;
        this.restTemplateConfig = restTemplateConfig;
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

        String url = nodeInfo.getServiceUrl() + "/taniwha/node/metadata";
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

    public Map<String, Object> getServiceToken(String nodeId, String userTgtToken) {
        NodeInfo nodeInfo = getNodeInfo(nodeId);
        Map<String, Object> response = new HashMap<>();

        if (nodeInfo == null) {
            logger.error("Tried fetching node that doesnt exist");
            response.put(ERROR_KEY, NODE_NOT_FOUND_MESSAGE);
        } else {
            // Access the node's principal
            String serviceToken;
            try {
                serviceToken = kerberosService.requestSgt(userTgtToken, kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
            } catch (IOException | KrbException e) {
                logger.error("Error requesting the Sgt token", e);
                response.put(ERROR_KEY, NODE_NOT_FOUND_MESSAGE);
                return response;
            }

            if (serviceToken != null) {
                boolean success = sendTokenToNode(nodeInfo, serviceToken);
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

    private boolean sendTokenToNode(NodeInfo nodeInfo, String sgtToken) {
        String url = nodeInfo.getServiceUrl() + "/taniwha/node/authorize";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = new HashMap<>();
        body.put("token", sgtToken);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        try {
            logger.debug("Sending token to node URL: {}", url);
            RestTemplate restTemplate = restTemplateConfig.getRestTemplate();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            logger.debug("Sending token to node URL: {} with response: {}", url, response.getStatusCode());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            logger.error("Error connecting to node", e);
            return false;
        }
    }
}
