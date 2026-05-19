package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.model.NodeInfo;
import org.taniwha.repository.NodeRepository;
import org.taniwha.util.JwtTokenUtil;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class NodeAccessServiceTest {

    private NodeRepository nodeRepository;
    private JwtTokenUtil jwtTokenUtil;
    private KerberosService kerberosService;
    private UserService userService;
    private RestTemplate mockRestTemplate;
    private TrustedNodeProxyConfig trustedNodeConfigService;
    private TrustedNodeSignatureService trustedNodeMessageSecurityService;
    private NodeAccessService nodeAccessService;

    @BeforeEach
    void setUp() {
        nodeRepository = mock(NodeRepository.class);
        jwtTokenUtil = mock(JwtTokenUtil.class);
        kerberosService = mock(KerberosService.class);
        userService = mock(UserService.class);
        RestTemplateConfig restTemplateConfig = mock(RestTemplateConfig.class);
        mockRestTemplate = mock(RestTemplate.class);
        trustedNodeConfigService = mock(TrustedNodeProxyConfig.class);
        trustedNodeMessageSecurityService = mock(TrustedNodeSignatureService.class);
        when(restTemplateConfig.getRestTemplate()).thenReturn(mockRestTemplate);
        when(trustedNodeMessageSecurityService.verifyResponseIfNeeded(any(), any(), any(), any())).thenReturn(true);

        nodeAccessService = new NodeAccessService(
                nodeRepository,
                jwtTokenUtil,
                kerberosService,
                userService,
                restTemplateConfig,
                trustedNodeConfigService,
                trustedNodeMessageSecurityService,
                "TEST.REALM"
        );
    }

    @Test
    void testCheckUserAccess() {
        when(jwtTokenUtil.getUsernameFromToken("some_token")).thenReturn("some_user");
        when(userService.userHasAccessToNode(eq("some_user"), eq("node123"))).thenReturn(true);

        boolean hasAccess = nodeAccessService.checkUserAccess("node123", "some_token");
        assertTrue(hasAccess);
        verify(jwtTokenUtil).getUsernameFromToken("some_token");
        verify(userService).userHasAccessToNode("some_user", "node123");
    }

    @Test
    void testGetServiceToken_NodeNotFound() {
        when(nodeRepository.findById("missingNode")).thenReturn(Optional.empty());
        Map<String, Object> result = nodeAccessService.getServiceToken("missingNode", "someTgt");
        assertTrue(result.containsKey("error"));
        assertEquals("Node not found", result.get("error"));
        verify(nodeRepository).findById("missingNode");
    }

    @Test
    void testGetServiceToken_Success() throws IOException, KrbException {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");

        when(nodeRepository.findById("node123"))
                .thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(trustedNodeConfigService.resolveSharedSecret(nodeInfo)).thenReturn(Optional.empty());
        when(kerberosService.getPrincipalName(eq("http://localhost:8080"), eq("TEST.REALM")))
                .thenReturn("principalName");

        when(kerberosService.requestSgt(anyString(), anyString()))
                .thenReturn("mockServiceToken");

        ResponseEntity<byte[]> successResponse = new ResponseEntity<>("OK".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        when(mockRestTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(byte[].class))
        ).thenReturn(successResponse);
        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");
        assertTrue(result.containsKey("token"), "Should contain 'token' on success");
        assertEquals("mockServiceToken", result.get("token"));

        verify(nodeRepository).findById("node123");
        verify(kerberosService).requestSgt(eq("mockTgtToken"), anyString());
        verify(mockRestTemplate).exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(byte[].class)
        );
    }

    @Test
    void testGetServiceToken_RequestSgtFailure() throws IOException, KrbException {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");

        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(kerberosService.getPrincipalName(eq("http://localhost:8080"), eq("TEST.REALM"))).thenReturn("principalName");
        when(kerberosService.requestSgt(anyString(), anyString())).thenReturn(null);
        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");

        assertTrue(result.containsKey("error"));
        assertEquals("Failed to generate token. Node can't be reached", result.get("error"));
    }

    @Test
    void testGetServiceToken_SendTokenFailure() throws IOException, KrbException {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");

        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(trustedNodeConfigService.resolveSharedSecret(nodeInfo)).thenReturn(Optional.empty());
        when(kerberosService.getPrincipalName(eq("http://localhost:8080"), eq("TEST.REALM")))
                .thenReturn("principalName");

        when(kerberosService.requestSgt(anyString(), anyString())).thenReturn("mockServiceToken");
        when(mockRestTemplate.exchange(
                any(URI.class),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(byte[].class))
        ).thenThrow(new RestClientException("Connection error"));

        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");
        assertTrue(result.containsKey("error"));
        assertTrue(result.get("error").toString().contains("Failed to connect to node TestNode"));
        verify(nodeRepository).findById("node123");
    }

    @Test
    void testCheckUserAccess_NoAccess() {
        when(jwtTokenUtil.getUsernameFromToken("token")).thenReturn("user");
        when(userService.userHasAccessToNode("user", "node123")).thenReturn(false);

        boolean hasAccess = nodeAccessService.checkUserAccess("node123", "token");

        assertFalse(hasAccess);
    }

    @Test
    void testGetServiceToken_KrbException() throws IOException, KrbException {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");

        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(kerberosService.getPrincipalName(eq("http://localhost:8080"), eq("TEST.REALM")))
                .thenReturn("principalName");
        when(kerberosService.requestSgt(anyString(), anyString()))
                .thenThrow(new KrbException("Kerberos error"));

        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");

        assertTrue(result.containsKey("error"));
        assertEquals("Failed to request Kerberos service ticket for node TestNode", result.get("error"));
    }

    @Test
    void testGetServiceToken_IOException() throws IOException, KrbException {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");

        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(kerberosService.getPrincipalName(eq("http://localhost:8080"), eq("TEST.REALM")))
                .thenReturn("principalName");
        when(kerberosService.requestSgt(anyString(), anyString()))
                .thenThrow(new IOException("IO error"));

        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");

        assertTrue(result.containsKey("error"));
        assertEquals("Failed to request Kerberos service ticket for node TestNode", result.get("error"));
    }

    @Test
    void testIsFairDataPointEnabled_returnsTrueWhenProbeSucceeds() {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(mockRestTemplate.exchange(
                eq("http://localhost:8080/taniwha/fdp"),
                eq(HttpMethod.GET),
                argThat((HttpEntity<?> request) -> {
                    HttpHeaders headers = request.getHeaders();
                    return headers.getAccept().size() == 1
                            && MediaType.valueOf("text/turtle").equals(headers.getAccept().get(0));
                }),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("fdp"));

        boolean enabled = nodeAccessService.isFairDataPointEnabled("node123");

        assertTrue(enabled);
    }

    @Test
    void testIsFairDataPointEnabled_returnsFalseWhenProbeFails() {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(mockRestTemplate.exchange(
                eq("http://localhost:8080/taniwha/fdp"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        boolean enabled = nodeAccessService.isFairDataPointEnabled("node123");

        assertFalse(enabled);
    }

    @Test
    void testGetServiceToken_requiresTrustedHttpRouteForHttpNodes() {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");
        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.empty());

        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");

        assertEquals("HTTP access is not enabled for node TestNode", result.get("error"));
        verifyNoInteractions(kerberosService);
    }

    @Test
    void testGetServiceToken_signsAuthorizeRequestsWhenSharedSecretConfigured() throws Exception {
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setNodeId("node123");
        nodeInfo.setIp("http://localhost:8080");
        nodeInfo.setName("TestNode");

        when(nodeRepository.findById("node123")).thenReturn(Optional.of(nodeInfo));
        when(trustedNodeConfigService.resolveUpstreamBaseUrl(nodeInfo)).thenReturn(Optional.of("http://localhost:8080"));
        when(trustedNodeConfigService.resolveSharedSecret(nodeInfo)).thenReturn(Optional.of("secret"));
        when(kerberosService.getPrincipalName(eq("http://localhost:8080"), eq("TEST.REALM")))
                .thenReturn("principalName");
        when(kerberosService.requestSgt(anyString(), anyString())).thenReturn("mockServiceToken");
        when(trustedNodeMessageSecurityService.signRequestIfNeeded(
                eq(HttpMethod.POST),
                eq(URI.create("http://localhost:8080/taniwha/node/authorize")),
                any(HttpHeaders.class),
                any(byte[].class),
                eq(Optional.of("secret"))
        )).thenAnswer(invocation -> {
            HttpHeaders headers = invocation.getArgument(2);
            headers.set("X-Taniwha-Signature", "signed");
            return Optional.of(new TrustedNodeSignatureService.SignedRequestContext(
                    "POST", "/taniwha/node/authorize", "nonce", "secret"
            ));
        });

        ResponseEntity<byte[]> successResponse = new ResponseEntity<>("OK".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        when(mockRestTemplate.exchange(
                eq(URI.create("http://localhost:8080/taniwha/node/authorize")),
                eq(HttpMethod.POST),
                argThat((HttpEntity<byte[]> entity) -> "signed".equals(entity.getHeaders().getFirst("X-Taniwha-Signature"))),
                eq(byte[].class)
        )).thenReturn(successResponse);

        Map<String, Object> result = nodeAccessService.getServiceToken("node123", "mockTgtToken");

        assertEquals("mockServiceToken", result.get("token"));
    }
}
