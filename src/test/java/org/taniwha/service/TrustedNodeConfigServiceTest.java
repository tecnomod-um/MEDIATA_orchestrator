package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.model.NodeInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TrustedNodeProxyConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveRoute_loadsOptionalUpstreamAndSharedSecret() throws Exception {
        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(
                configFile,
                "http://stratifscuba.mediata.dev:8022|http://10.0.0.15:8080|shared-secret\n",
                StandardCharsets.UTF_8
        );

        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(configFile.toString());
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setIp("http://stratifscuba.mediata.dev:8022");

        Optional<TrustedNodeProxyConfig.TrustedNodeRoute> route = service.resolveRoute(nodeInfo);

        assertTrue(route.isPresent());
        assertEquals("http://stratifscuba.mediata.dev:8022", route.get().publicOrigin());
        assertEquals("http://10.0.0.15:8080", route.get().upstreamBaseUrl());
        assertEquals("shared-secret", route.get().sharedSecret());
        assertTrue(service.requiresProxy(nodeInfo));
        assertEquals("/nodes/proxy/node1", service.proxyBasePath("node1"));
        assertEquals(Optional.of("http://10.0.0.15:8080"), service.resolveUpstreamBaseUrl(nodeInfo));
        assertEquals(Optional.of("shared-secret"), service.resolveSharedSecret(nodeInfo));
    }

    @Test
    void resolveUpstreamBaseUrl_keepsHttpsNodesDirect() {
        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(tempDir.resolve("missing.config").toString());
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setIp("https://secure.example/taniwha");

        assertFalse(service.requiresProxy(nodeInfo));
        assertEquals(Optional.of("https://secure.example/taniwha"), service.resolveUpstreamBaseUrl(nodeInfo));
        assertEquals(Optional.empty(), service.resolveSharedSecret(nodeInfo));
    }

    @Test
    void resolveRoute_rejectsHttpNodesMissingFromTrustedConfig() throws Exception {
        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(configFile, "# empty\n", StandardCharsets.UTF_8);
        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(configFile.toString());
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setIp("http://untrusted.example:8080");

        assertFalse(service.requiresProxy(nodeInfo));
        assertEquals(Optional.empty(), service.resolveUpstreamBaseUrl(nodeInfo));
    }
}
