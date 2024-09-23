package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.model.NodeInfo;
import org.taniwha.repository.NodeRepository;
import org.taniwha.util.JwtTokenUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// Functionality related to users accessing a node securely
@Service
public class NodeAccessService {

    private static final Logger logger = LoggerFactory.getLogger(NodeAccessService.class);
    private static final String ERROR = "error";
    private final NodeRepository nodeRepository;
    private final JwtTokenUtil jwtTokenUtil;
    private final KerberosService kerberosService;
    private final UserService userService;
    private final String realm;

    @Autowired
    public NodeAccessService(NodeRepository nodeRepository, JwtTokenUtil jwtTokenUtil,
                             KerberosService kerberosService, UserService userService,
                             @Value("${kerberos.realm}") String realm) {
        this.nodeRepository = nodeRepository;
        this.jwtTokenUtil = jwtTokenUtil;
        this.kerberosService = kerberosService;
        this.userService = userService;
        this.realm = realm;
    }

    public boolean checkUserAccess(String nodeId, String userToken) {
        String username = jwtTokenUtil.getUsernameFromToken(userToken);
        return userService.userHasAccessToNode(username, nodeId);
    }

    private NodeInfo getNodeInfo(String nodeId) {
        return nodeRepository.findById(nodeId).orElse(null);
    }

    public Map<String, Object> getServiceToken(String nodeId, String userTgtToken) {
        NodeInfo nodeInfo = getNodeInfo(nodeId);
        Map<String, Object> response = new HashMap<>();

        if (nodeInfo != null) {
            // Access the node's principal
            String serviceToken;

            try {
                serviceToken = kerberosService.requestSgt(userTgtToken, kerberosService.getPrincipalName(nodeInfo.getIp(), realm));
            } catch (IOException | KrbException e) {
                logger.error("Error requesting the Sgt token", e);
                return new HashMap<>();
            }

            if (serviceToken != null) {
                boolean success = sendTokenToNode(nodeInfo, serviceToken);
                if (success)
                    response.put("token", serviceToken);
                else
                    response.put(ERROR, "Failed to connect to node " + nodeInfo.getName());
            } else
                response.put(ERROR, "Failed to generate token. Node can't be reached");
            return response;
        }
        response.put(ERROR, "Node not found");
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
            ResponseEntity<String> response = new RestTemplate().exchange(url, HttpMethod.POST, request, String.class);
            logger.debug("Sending token to node URL: {} with response: {}", url, response.getStatusCode());
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            logger.error("Error connecting to node", e);
            return false;
        }
    }
}
