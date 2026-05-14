package org.taniwha.controller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestTemplate;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeService;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.service.TrustedNodeSignatureService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NodeProxyControllerIT {

    @TempDir
    Path tempDir;

    private HttpServer httpServer;
    private MockMvc mockMvc;
    private NodeInfo nodeInfo;
    private final List<CapturedRequest> capturedRequests = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", this::handleRequest);
        httpServer.start();

        int port = httpServer.getAddress().getPort();
        String serviceUrl = "http://127.0.0.1:" + port;
        nodeInfo = new NodeInfo("node1", serviceUrl, "Node", null, "", "#fff", null);

        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(configFile, serviceUrl + System.lineSeparator(), StandardCharsets.UTF_8);

        NodeService nodeService = mock(NodeService.class);
        when(nodeService.findNodeById("node1")).thenReturn(nodeInfo);

        TrustedNodeProxyConfig trustedNodeConfigService = new TrustedNodeProxyConfig(configFile.toString());
        TrustedNodeSignatureService trustedNodeMessageSecurityService = new TrustedNodeSignatureService();

        RestTemplateConfig restTemplateConfig = mock(RestTemplateConfig.class);
        when(restTemplateConfig.getRestTemplate()).thenReturn(new RestTemplate());

        mockMvc = MockMvcBuilders.standaloneSetup(
                new NodeProxyController(
                        nodeService,
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
            responseBody = "[{\"ok\":true}]";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "http://localhost:3000");
        }

        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String authorization, String body) {
    }
}
