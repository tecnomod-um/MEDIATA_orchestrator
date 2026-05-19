package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeSummaryTest {

    @Test
    void testNodeSummaryAllArgsConstructor() {
        NodeSummary summary = new NodeSummary("node1", "Test Node", "A test node", "#FF0000", "https://example.org/taniwha");

        assertEquals("node1", summary.getNodeId());
        assertEquals("Test Node", summary.getName());
        assertEquals("A test node", summary.getDescription());
        assertEquals("#FF0000", summary.getColor());
        assertEquals("https://example.org/taniwha", summary.getServiceUrl());
        assertFalse(summary.isProxyRequired());
        assertNull(summary.getProxyBasePath());
    }

    @Test
    void testNodeSummarySetters() {
        NodeSummary summary = new NodeSummary("id", "name", "desc", "color", "https://old.example/taniwha");

        summary.setNodeId("newId");
        summary.setName("New Name");
        summary.setDescription("New description");
        summary.setColor("#00FF00");
        summary.setServiceUrl("https://new.example/taniwha");
        summary.setProxyRequired(true);
        summary.setProxyBasePath("/nodes/proxy/newId");

        assertEquals("newId", summary.getNodeId());
        assertEquals("New Name", summary.getName());
        assertEquals("New description", summary.getDescription());
        assertEquals("#00FF00", summary.getColor());
        assertEquals("https://new.example/taniwha", summary.getServiceUrl());
        assertTrue(summary.isProxyRequired());
        assertEquals("/nodes/proxy/newId", summary.getProxyBasePath());
    }

    @Test
    void testNodeSummaryWithNullValues() {
        NodeSummary summary = new NodeSummary(null, null, null, null, null);

        assertNull(summary.getNodeId());
        assertNull(summary.getName());
        assertNull(summary.getDescription());
        assertNull(summary.getColor());
        assertNull(summary.getServiceUrl());
        assertFalse(summary.isProxyRequired());
        assertNull(summary.getProxyBasePath());
    }

    @Test
    void testNodeSummaryColorCodes() {
        NodeSummary summary1 = new NodeSummary("1", "Node1", "Desc1", "red", "https://one.example/taniwha");
        NodeSummary summary2 = new NodeSummary("2", "Node2", "Desc2", "blue", "https://two.example/taniwha");

        assertNotEquals(summary1.getColor(), summary2.getColor());
        assertNotEquals(summary1.getNodeId(), summary2.getNodeId());
    }

    @Test
    void testNodeSummaryUpdate() {
        NodeSummary summary = new NodeSummary("initial", "Init", "Init desc", "red", "https://initial.example/taniwha");

        assertEquals("initial", summary.getNodeId());

        summary.setNodeId("updated");
        summary.setServiceUrl("https://updated.example/taniwha");
        summary.setProxyRequired(true);
        summary.setProxyBasePath("/nodes/proxy/updated");
        assertEquals("updated", summary.getNodeId());
        assertEquals("https://updated.example/taniwha", summary.getServiceUrl());
        assertTrue(summary.isProxyRequired());
        assertEquals("/nodes/proxy/updated", summary.getProxyBasePath());
    }
}
