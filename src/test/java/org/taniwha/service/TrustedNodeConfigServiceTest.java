package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.taniwha.config.TrustedNodeProxyConfig;
import org.taniwha.model.NodeInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
                "http://stratifscuba.mediata.dev:8022|http://host.docker.internal:8080|12345678901234567890123456789012\n",
                StandardCharsets.UTF_8
        );

        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(
                configFile.toString(), "host.docker.internal,10.0.0.15,localhost");
        NodeInfo nodeInfo = new NodeInfo();
        nodeInfo.setIp("http://stratifscuba.mediata.dev:8022");

        Optional<TrustedNodeProxyConfig.TrustedNodeRoute> route = service.resolveRoute(nodeInfo);

        assertTrue(route.isPresent());
        assertEquals("http://stratifscuba.mediata.dev:8022", route.get().publicOrigin());
        assertEquals("http://host.docker.internal:8080", route.get().upstreamBaseUrl());
        assertEquals("12345678901234567890123456789012", route.get().sharedSecret());
        assertTrue(service.requiresProxy(nodeInfo));
        assertEquals("/nodes/proxy/node1", service.proxyBasePath("node1"));
        assertEquals(Optional.of("http://host.docker.internal:8080"), service.resolveUpstreamBaseUrl(nodeInfo));
        assertEquals(Optional.of("12345678901234567890123456789012"), service.resolveSharedSecret(nodeInfo));
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

    @Test
    void resolveRoute_usesPublicOriginWhenUpstreamIsOmitted() throws Exception {
        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(configFile, "http://node.example:8080||12345678901234567890123456789012\n", StandardCharsets.UTF_8);
        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(configFile.toString(), "node.example");

        Optional<TrustedNodeProxyConfig.TrustedNodeRoute> route =
                service.resolveRoute("http://node.example:8080/taniwha");

        assertTrue(route.isPresent());
        assertEquals("http://node.example:8080", route.get().publicOrigin());
        assertEquals("http://node.example:8080", route.get().upstreamBaseUrl());
        assertEquals("12345678901234567890123456789012", route.get().sharedSecret());
        assertTrue(service.requiresProxy("http://node.example:8080/taniwha"));
    }

    @Test
    void resolveRoute_ignoresInvalidEntriesAndDefaultPortsMatchOrigins() throws Exception {
        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(
                configFile,
                String.join(System.lineSeparator(),
                        "not-a-url",
                        "https://secure.example",
                        "http://public.example|::::bad-upstream",
                        "http://default-port.example|http://localhost:9090|   ",
                        ""
                ),
                StandardCharsets.UTF_8
        );
        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(configFile.toString());

        assertFalse(service.requiresProxy("not-a-url"));
        assertFalse(service.requiresProxy("https://secure.example"));
        assertFalse(service.requiresProxy((String) null));
        assertFalse(service.requiresProxy("http://default-port.example/path"));

        Optional<TrustedNodeProxyConfig.TrustedNodeRoute> route =
                service.resolveRoute("http://default-port.example");
        assertFalse(route.isPresent());
    }

    @Test
    void resolveRoute_rejectsUnallowlistedUpstreamAndDisabledHttp() throws Exception {
        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(configFile,
                "http://node.example:8080|http://upstream.example:8080|12345678901234567890123456789012\n",
                StandardCharsets.UTF_8);
        TrustedNodeProxyConfig disallowed = new TrustedNodeProxyConfig(configFile.toString(), "localhost");
        assertFalse(disallowed.requiresProxy("http://node.example:8080"));

        TrustedNodeProxyConfig httpDisabled = new TrustedNodeProxyConfig(configFile.toString(), "upstream.example");
        org.springframework.test.util.ReflectionTestUtils.setField(httpDisabled, "allowHttpUpstream", false);
        assertFalse(httpDisabled.requiresProxy("http://node.example:8080"));
    }

    @Test
    void reloadIfNeeded_picksUpConfigFileChanges() throws Exception {
        Path configFile = tempDir.resolve("trusted-servers.config");
        Files.writeString(configFile, "http://old.example|http://localhost:8080|12345678901234567890123456789012\n", StandardCharsets.UTF_8);
        TrustedNodeProxyConfig service = new TrustedNodeProxyConfig(configFile.toString());

        assertTrue(service.requiresProxy("http://old.example"));
        assertFalse(service.requiresProxy("http://new.example"));

        Files.writeString(configFile, "http://new.example|http://localhost:8080|12345678901234567890123456789012\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(configFile, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        assertFalse(service.requiresProxy("http://old.example"));
        assertTrue(service.requiresProxy("http://new.example"));
    }
}
