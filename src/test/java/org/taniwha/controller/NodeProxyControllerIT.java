package org.taniwha.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeAccessService;
import org.taniwha.service.NodeService;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.service.TrustedNodeSignatureService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NodeProxyControllerIT {

    private static final String SHARED_SECRET = "12345678901234567890123456789012";
    @TempDir
    Path tempDir;

    private HttpServer httpServer;
    private MockMvc mockMvc;
    private NodeService nodeService;
    private NodeAccessService nodeAccessService;
    private NodeInfo nodeInfo;
    private final List<CapturedRequest> capturedRequests = new ArrayList<>();
    private String customResponseBody;
    private boolean tamperSignature;
    private int responseStatus = 200;

    @BeforeEach
    void setUp() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", this::handleRequest);
        httpServer.start();

        int port = httpServer.getAddress().getPort();
        String serviceUrl = "http://127.0.0.1:" + port;
        nodeInfo = new NodeInfo("node1", serviceUrl, "Node", null, "", "#fff", null);

        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(configFile, serviceUrl + "|"
                + serviceUrl + "|" + SHARED_SECRET
                + System.lineSeparator(), StandardCharsets.UTF_8);

        nodeService = mock(NodeService.class);
        when(nodeService.findNodeById("node1")).thenReturn(nodeInfo);
        nodeAccessService = mock(NodeAccessService.class);
        when(nodeAccessService.checkUserAccess("node1", "central-token")).thenReturn(true);

        TrustedNodeProxyConfig trustedNodeConfigService = new TrustedNodeProxyConfig(configFile.toString());
        TrustedNodeSignatureService trustedNodeMessageSecurityService = new TrustedNodeSignatureService();

        RestTemplateConfig restTemplateConfig = mock(RestTemplateConfig.class);
        when(restTemplateConfig.getNodeProxyRestTemplate()).thenReturn(new RestTemplate());

        mockMvc = MockMvcBuilders.standaloneSetup(
                new NodeProxyController(
                        nodeService,
                        nodeAccessService,
                        trustedNodeConfigService,
                        trustedNodeMessageSecurityService,
                        restTemplateConfig
                )
        ).build();
    }

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        capturedRequests.clear();
    }

    @Test
    void proxyCannotRelayDatasetRecordsEvenForAnAuthorisedNodeUser() throws Exception {
        for (String path : List.of("/taniwha/api/files/datasets/patient.csv", "/taniwha/api/files/datasets/patient.xlsx",
                "/taniwha/api/files/%64atasets/patient.csv", "/taniwha/api/files/%2564atasets/patient.csv",
                "/taniwha/api/files/datasets;v=1/patient.csv")) {
            mockMvc.perform(get(URI.create("/nodes/proxy/node1" + path))
                            .header("Authorization", "Bearer central-token")
                            .header("X-Node-Authorization", "Bearer node-token"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head(URI.create("/nodes/proxy/node1" + path))
                            .header("Authorization", "Bearer central-token")
                            .header("X-Node-Authorization", "Bearer node-token"))
                    .andExpect(status().isForbidden());
        }
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void proxyNodeRequestTunnelsPairedAggregateSummariesAndFiltersWithoutChangingThem() throws Exception {
        customResponseBody = "[{\"fileName\":\"cohort.csv\",\"relationshipSummaryVersion\":1,"
                + "\"continuousFeatures\":[{\"featureName\":\"Age\",\"count\":24,\"mean\":61.5,"
                + "\"sumCubedDeviations\":42.0,\"sumFourthDeviations\":840.0,\"sampleStdDev\":4.0,"
                + "\"skewness\":0.12,\"kurtosis\":-0.5,\"meanCiLower\":59.1,\"meanCiUpper\":63.9,"
                + "\"modes\":[60.0,65.0],\"modeFrequency\":6,\"modeStatus\":\"Available\"}],"
                + "\"numericPairSummaries\":[{\"featureX\":\"Age\",\"featureY\":\"FIM\",\"count\":24,"
                + "\"meanX\":61.5,\"meanY\":42.0,\"m2X\":360.0,\"m2Y\":480.0,\"crossMoment\":120.0,\"frequencies\":[]}]}]";
        String operators = "{\"multipleFileFilters\":[{\"fileName\":\"cohort.csv\",\"filters\":{\"conditions\":{\"cluster\":1}}}]}";
        var response = mockMvc.perform(post("/nodes/proxy/node1/taniwha/api/data/filterByNameList")
                        .header("Authorization", "Bearer central-token")
                        .header("X-Node-Authorization", "Bearer node-token")
                        .contentType("application/json").content(operators))
                .andExpect(status().isOk()).andExpect(header().string("X-Node-Proxy", "true")).andReturn();
        assertEquals(customResponseBody, response.getResponse().getContentAsString());
        assertEquals(operators, capturedRequests.get(0).body());
        assertEquals("Bearer node-token", capturedRequests.get(0).authorization());
    }

    @Test
    void tamperedSuccessAndErrorResponsesNeverReachTheCaller() throws Exception {
        tamperSignature = true;
        customResponseBody = "synthetic-payload-must-not-be-relayed";
        for (int statusCode : List.of(200, 400)) {
            responseStatus = statusCode;
            var response = mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/data/processList/result/synthetic-job")
                            .header("Authorization", "Bearer central-token")
                            .header("X-Node-Authorization", "Bearer node-token"))
                    .andExpect(status().isBadGateway()).andReturn();
            assertEquals("Invalid proxied node response signature", response.getResponse().getContentAsString());
        }
    }

    @Test
    void signedNodeErrorRetainsItsStatusAndSafeBody() throws Exception {
        responseStatus = 400;
        customResponseBody = "{\"message\":\"Error processing statistics.\"}";
        var response = mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/data/processList/result/synthetic-job")
                        .header("Authorization", "Bearer central-token")
                        .header("X-Node-Authorization", "Bearer node-token"))
                .andExpect(status().isBadRequest()).andReturn();
        assertEquals(customResponseBody, response.getResponse().getContentAsString());
    }

    @Test
    void unavailableNodeReturnsAGenericFailure() throws Exception {
        httpServer.stop(0);
        var response = mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/files/datasets")
                        .header("Authorization", "Bearer central-token")
                        .header("X-Node-Authorization", "Bearer node-token"))
                .andExpect(status().isBadGateway()).andReturn();
        assertEquals("Failed to reach proxied node", response.getResponse().getContentAsString());
    }

    @Test
    void proxyNodeRequest_forwardsValidateTrafficToTrustedHttpNode() throws Exception {
        MvcResult result = mockMvc.perform(post("/nodes/proxy/node1/taniwha/node/validate")
                        .header("Authorization", "Bearer central-token")
                        .contentType("application/json")
                        .content("{\"kerberosToken\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Node-Proxy", "true"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals("{\"jwtNodeToken\":\"NODE\"}", response.getContentAsString());
        assertEquals(1, capturedRequests.size());

        CapturedRequest request = capturedRequests.get(0);
        assertEquals(HttpMethod.POST.name(), request.method());
        assertEquals("/taniwha/node/validate", request.path());
        assertEquals("Bearer central-token", request.authorization());
        assertTrue(request.body().contains("kerberosToken"));
    }

    @Test
    void proxyNodeRequest_forwardsNodeJwtForSubsequentRequests() throws Exception {
        MvcResult result = mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/files/datasets")
                        .queryParam("limit", "5")
                        .header("Authorization", "Bearer central-token")
                        .header("X-Node-Authorization", "Bearer node-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Node-Proxy", "true"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andReturn();

        MockHttpServletResponse response = result.getResponse();
        assertEquals("[{\"ok\":true}]", response.getContentAsString());
        assertEquals(1, capturedRequests.size());

        CapturedRequest request = capturedRequests.get(0);
        assertEquals(HttpMethod.GET.name(), request.method());
        assertEquals("/taniwha/api/files/datasets?limit=5", request.path());
        assertEquals("Bearer node-token", request.authorization());
    }

    @Test
    void proxyNodeRequest_returnsNotFoundWhenNodeDoesNotExist() throws Exception {
        MvcResult result = mockMvc.perform(get("/nodes/proxy/missing/taniwha/api/files/datasets")
                        .header("Authorization", "Bearer central-token"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Node-Proxy", "true"))
                .andReturn();

        assertEquals("Node not found", result.getResponse().getContentAsString());
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void proxyNodeRequest_returnsForbiddenWhenHttpNodeIsNotTrusted() throws Exception {
        when(nodeService.findNodeById("untrusted"))
                .thenReturn(new NodeInfo("untrusted", "http://untrusted.example:8080", "Node", null, "", "#fff", null));
        when(nodeAccessService.checkUserAccess("untrusted", "central-token")).thenReturn(true);

        MvcResult result = mockMvc.perform(get("/nodes/proxy/untrusted/taniwha/api/files/datasets")
                        .header("Authorization", "Bearer central-token"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Node-Proxy", "true"))
                .andReturn();

        assertEquals("Proxying is not enabled for this node", result.getResponse().getContentAsString());
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void proxyNodeRequest_rejectsAuthenticatedUserWithoutNodeAccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/files/datasets")
                        .header("Authorization", "Bearer other-token"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("X-Node-Proxy", "true"))
                .andReturn();

        assertEquals("Node access denied", result.getResponse().getContentAsString());
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void proxyNodeRequest_requiresAuthentication() throws Exception {
        MvcResult result = mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/files/datasets"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Node-Proxy", "true"))
                .andReturn();

        assertEquals("Authentication required", result.getResponse().getContentAsString());
        assertTrue(capturedRequests.isEmpty());
    }

    @Test
    void proxyNodeRequest_isolatesTimeoutAndServesTheNextRequest() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        doAnswer(invocation -> {
            throw new ResourceAccessException(
                    "Read timed out", new SocketTimeoutException("Read timed out"));
        }).doAnswer(invocation -> {
            URI target = invocation.getArgument(0);
            HttpMethod method = invocation.getArgument(1);
            HttpEntity<?> entity = invocation.getArgument(2);
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            String timestamp = Long.toString(System.currentTimeMillis() / 1000);
            String contentHash = sha256Base64(body);
            String nonce = entity.getHeaders().getFirst("X-Taniwha-Nonce");
            String canonical = String.join("\n", "RESP", "200", method.name(),
                    target.getRawPath(), nonce, timestamp, contentHash);
            HttpHeaders headers = new HttpHeaders();
            headers.add("X-Taniwha-Response-Timestamp", timestamp);
            headers.add("X-Taniwha-Response-Content-SHA256", contentHash);
            headers.add("X-Taniwha-Response-Signature", hmacBase64(canonical));
            return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
        }).when(restTemplate).exchange(
                any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(byte[].class));

        RestTemplateConfig restTemplateConfig = mock(RestTemplateConfig.class);
        when(restTemplateConfig.getNodeProxyRestTemplate()).thenReturn(restTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new NodeProxyController(
                        nodeService,
                        nodeAccessService,
                        new TrustedNodeProxyConfig(tempDir.resolve("trusted-servers.config").toString()),
                        new TrustedNodeSignatureService(),
                        restTemplateConfig
                )
        ).build();

        mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/files/datasets")
                        .header("Authorization", "Bearer central-token")
                        .header("X-Node-Authorization", "Bearer node-token"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(header().string("X-Node-Proxy", "true"));

        mockMvc.perform(get("/nodes/proxy/node1/taniwha/api/files/datasets")
                        .header("Authorization", "Bearer central-token")
                        .header("X-Node-Authorization", "Bearer node-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Node-Proxy", "true"));
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedRequests.add(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body
        ));

        String responseBody;
        if ("/taniwha/node/validate".equals(exchange.getRequestURI().getPath())) {
            responseBody = "{\"jwtNodeToken\":\"NODE\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:3000");
        } else {
            responseBody = customResponseBody == null ? "[{\"ok\":true}]" : customResponseBody;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:3000");
        }

        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String contentHash = sha256Base64(bytes);
        String nonce = exchange.getRequestHeaders().getFirst("X-Taniwha-Nonce");
        String pathAndQuery = exchange.getRequestURI().toString();
        String canonical = String.join("\n", "RESP", Integer.toString(responseStatus), exchange.getRequestMethod(),
                pathAndQuery, nonce, timestamp, contentHash);
        exchange.getResponseHeaders().add("X-Taniwha-Response-Timestamp", timestamp);
        exchange.getResponseHeaders().add("X-Taniwha-Response-Content-SHA256", contentHash);
        exchange.getResponseHeaders().add("X-Taniwha-Response-Signature", tamperSignature ? "tampered" : hmacBase64(canonical));
        exchange.sendResponseHeaders(responseStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String sha256Base64(byte[] body) throws IOException {
        try {
            return Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(body));
        } catch (Exception e) {
            throw new IOException("Unable to hash test response", e);
        }
    }

    private String hmacBase64(String payload) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SHARED_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IOException("Unable to sign test response", e);
        }
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
