package org.taniwha.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeAccessService;
import org.taniwha.service.NodeService;
import org.taniwha.service.TrustedNodeSignatureService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in network smoke test against three isolated Java nodes with synthetic data only. */
@EnabledIfSystemProperty(named = "mediata.statistics.smokeFixture", matches = ".+")
class DiscoveryFederationSmokeTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realNodesReleaseSignedSummariesAndPreserveDisclosureControlsThroughTheProxy() throws Exception {
        JsonNode fixture = mapper.readTree(Path.of(System.getProperty("mediata.statistics.smokeFixture")).toFile());
        String secret = System.getProperty("mediata.statistics.smokeSecret");
        assertNotNull(secret, "An explicit synthetic-node shared secret is required");
        MockMvc proxy = createProxy(fixture, secret);

        for (boolean filtered : new boolean[]{false, true}) {
            long pairedCount = 0;
            for (JsonNode node : fixture.get("nodes")) {
                String filters = filtered ? "{\"operator\":\"AND\",\"conditions\":{\"Cluster\":{\"conditions\":[{\"type\":\"equal\",\"value\":\"1\",\"filterType\":\"continuous\"}],\"operators\":[]}}}" : "null";
                JsonNode response = query(proxy, fixture, node, "pooled-demo.csv", filters);
                assertEquals(2, response.get("disclosurePolicyVersion").asInt());
                assertEquals(1, response.get("relationshipSummaryVersion").asInt());
                String x = node.get("nodeId").asText().equals("beta") ? "OtherX" : "X";
                boolean found = false;
                for (JsonNode pair : response.get("numericPairSummaries")) {
                    String a = pair.get("featureX").asText(), b = pair.get("featureY").asText();
                    if (a.equals(x) && b.equals("Y") || a.equals("Y") && b.equals(x)) {
                        pairedCount += pair.get("count").asLong(); found = true;
                        assertFalse(pair.get("frequencies").isEmpty());
                        for (JsonNode cell : pair.get("frequencies")) assertTrue(cell.get("count").asLong() >= 6);
                    }
                }
                assertTrue(found, "The signed response must retain complete paired summaries");
                for (JsonNode feature : response.get("continuousFeatures")) assertTrue(feature.get("outliers").isEmpty());
                proxy.perform(get("/nodes/proxy/" + node.get("nodeId").asText() + "/taniwha/api/files/datasets/pooled-demo.csv")
                        .header("Authorization", "Bearer synthetic-session"))
                        .andExpect(status().isForbidden());
            }
            assertEquals(filtered ? 216 : 432, pairedCount);
        }

        for (boolean filtered : new boolean[]{false, true}) {
            long total = 0;
            for (JsonNode node : fixture.get("nodes")) {
                String x = node.get("nodeId").asText().equals("beta") ? "OtherX" : "X";
                String filters = filtered ? "{\"operator\":\"AND\",\"conditions\":{\"Cluster\":{\"conditions\":[{\"type\":\"equal\",\"value\":\"1\",\"filterType\":\"continuous\"}],\"operators\":[]}}}" : "null";
                JsonNode typed = query(proxy, fixture, node, "pooled-demo.csv", filters, Map.of(x, "categorical", "Y", "categorical"));
                assertEquals(2, typed.get("disclosurePolicyVersion").asInt());
                assertEquals(1, typed.get("featureTypeSummaryVersion").asInt());
                assertEquals("categorical", typed.get("featureTypes").get(x).asText());
                boolean found = false;
                for (JsonNode pair : typed.get("categoricalPairSummaries")) {
                    String a = pair.get("featureX").asText(), b = pair.get("featureY").asText();
                    if (a.equals(x) && b.equals("Y") || a.equals("Y") && b.equals(x)) {
                        total += pair.get("count").asLong(); found = true;
                    }
                }
                assertTrue(found, "Type choices must survive signed proxy and filtered recalculation");
                for (JsonNode pair : typed.get("numericPairSummaries")) {
                    assertNotEquals(x, pair.get("featureX").asText());
                    assertNotEquals("Y", pair.get("featureX").asText());
                    assertNotEquals(x, pair.get("featureY").asText());
                    assertNotEquals("Y", pair.get("featureY").asText());
                }
            }
            assertEquals(filtered ? 216 : 432, total);
        }
        JsonNode withheld = query(proxy, fixture, fixture.get("nodes").get(0), "paired-missing.csv",
                "{\"operator\":\"AND\",\"conditions\":{\"x\":{\"conditions\":[{\"type\":\"less\",\"value\":\"8\",\"filterType\":\"continuous\"}],\"operators\":[]}}}");
        assertTrue(withheld.get("message").asText().startsWith("Statistics withheld by disclosure controls."));
        assertTrue(withheld.get("continuousFeatures").isEmpty());
        assertTrue(withheld.get("numericPairSummaries").isEmpty());
    }

    @Test
    @EnabledIfSystemProperty(named = "mediata.statistics.largeFixture", matches = ".+")
    void asyncRefreshPollingAndCancellationRemainSignedAndAggregateOnly() throws Exception {
        JsonNode fixture = mapper.readTree(Path.of(System.getProperty("mediata.statistics.largeFixture")).toFile());
        JsonNode node = fixture.get("nodes").get(0);
        MockMvc proxy = createProxy(fixture, System.getProperty("mediata.statistics.smokeSecret"));
        String root = "/nodes/proxy/" + node.get("nodeId").asText() + "/taniwha/api/data/";
        String token = "Bearer " + fixture.get("tokens").get(node.get("serviceUrl").asText()).asText();
        String body = "{\"multipleFileFilters\":[{\"fileName\":\"large.csv\",\"featureTypes\":{\"Cluster\":\"categorical\"}}]}";
        var started = proxy.perform(post(root + "filterByNameList").header("Authorization", "Bearer synthetic-session")
                .header("X-Node-Authorization", token).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn();
        String id = mapper.readTree(started.getResponse().getContentAsByteArray()).get("jobId").asText();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        for (;;) {
            var statusResult = proxy.perform(get(root + "processList/status/" + id).header("Authorization", "Bearer synthetic-session")
                    .header("X-Node-Authorization", token)).andExpect(status().isOk()).andReturn();
            JsonNode progress = mapper.readTree(statusResult.getResponse().getContentAsByteArray());
            assertTrue(progress.path("results").isNull() || progress.path("results").isMissingNode());
            if (progress.path("state").asText().equals("DONE")) break;
            assertEquals("RUNNING", progress.path("state").asText());
            assertTrue(System.nanoTime() < deadline, "Large refresh must complete within the smoke-test deadline");
            Thread.sleep(100);
        }
        var completed = proxy.perform(get(root + "processList/result/" + id).header("Authorization", "Bearer synthetic-session")
                .header("X-Node-Authorization", token)).andExpect(status().isOk()).andReturn();
        JsonNode release = mapper.readTree(completed.getResponse().getContentAsByteArray()).get(0);
        assertEquals(2, release.path("disclosurePolicyVersion").asInt());
        assertEquals("categorical", release.path("featureTypes").path("Cluster").asText());
        assertTrue(release.path("continuousFeatures").findValues("outliers").stream().allMatch(JsonNode::isEmpty));
        assertEquals(55296, release.path("continuousFeatures").get(0).path("count").asInt());

        var second = proxy.perform(post(root + "filterByNameList").header("Authorization", "Bearer synthetic-session")
                .header("X-Node-Authorization", token).contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andReturn();
        String canceled = mapper.readTree(second.getResponse().getContentAsByteArray()).get("jobId").asText();
        var cancellation = proxy.perform(post(root + "processList/cancel/" + canceled).header("Authorization", "Bearer synthetic-session")
                .header("X-Node-Authorization", token)).andExpect(status().isOk()).andReturn();
        assertEquals("CANCELED", mapper.readTree(cancellation.getResponse().getContentAsByteArray()).path("state").asText());
        proxy.perform(get(root + "processList/result/" + canceled).header("Authorization", "Bearer synthetic-session")
                .header("X-Node-Authorization", token)).andExpect(status().isGone());
    }

    private MockMvc createProxy(JsonNode fixture, String secret) throws Exception {
        NodeService nodes = mock(NodeService.class);
        NodeAccessService access = mock(NodeAccessService.class);
        StringBuilder routes = new StringBuilder();
        for (JsonNode node : fixture.get("nodes")) {
            String id = node.get("nodeId").asText(), url = node.get("serviceUrl").asText();
            assertTrue(url.startsWith("http://127.0.0.1:"), "Only isolated loopback test nodes are supported");
            when(nodes.findNodeById(id)).thenReturn(new NodeInfo(id, url, id, null, "", "#fff", null));
            when(access.checkUserAccess(id, "synthetic-session")).thenReturn(true);
            routes.append(url).append('|').append(url).append('|').append(secret).append('\n');
        }

        Path config = temporary.resolve("trusted-servers.config");
        Files.writeString(config, routes);
        RestTemplateConfig clients = mock(RestTemplateConfig.class);
        when(clients.getNodeProxyRestTemplate()).thenReturn(new RestTemplate());
        return MockMvcBuilders.standaloneSetup(new NodeProxyController(nodes, access,
                new TrustedNodeProxyConfig(config.toString()), new TrustedNodeSignatureService(), clients)).build();

    }

    private JsonNode query(MockMvc proxy, JsonNode fixture, JsonNode node, String file, String filters) throws Exception {
        return query(proxy, fixture, node, file, filters, Map.of());
    }

    private JsonNode query(MockMvc proxy, JsonNode fixture, JsonNode node, String file, String filters, Map<String, String> types) throws Exception {
        var body = mapper.createObjectNode();
        var source = body.putArray("multipleFileFilters").addObject().put("fileName", file);
        source.set("filters", mapper.readTree(filters));
        source.set("featureTypes", mapper.valueToTree(types));
        var result = proxy.perform(post("/nodes/proxy/" + node.get("nodeId").asText() + "/taniwha/api/data/filterByNameList")
                        .header("Authorization", "Bearer synthetic-session")
                        .header("X-Node-Authorization", "Bearer " + fixture.get("tokens").get(node.get("serviceUrl").asText()).asText())
                        .contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray()).get(0);
    }
}
