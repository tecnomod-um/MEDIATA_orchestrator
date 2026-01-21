package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeSummaryTest {

    @Test
    void testNodeSummaryAllArgsConstructor() {
        NodeSummary summary = new NodeSummary("node1", "Test Node", "A test node", "#FF0000");
        
        assertEquals("node1", summary.getNodeId());
        assertEquals("Test Node", summary.getName());
        assertEquals("A test node", summary.getDescription());
        assertEquals("#FF0000", summary.getColor());
    }

    @Test
    void testNodeSummarySetters() {
        NodeSummary summary = new NodeSummary("id", "name", "desc", "color");
        
        summary.setNodeId("newId");
        summary.setName("New Name");
        summary.setDescription("New description");
        summary.setColor("#00FF00");
        
        assertEquals("newId", summary.getNodeId());
        assertEquals("New Name", summary.getName());
        assertEquals("New description", summary.getDescription());
        assertEquals("#00FF00", summary.getColor());
    }

    @Test
    void testNodeSummaryWithNullValues() {
        NodeSummary summary = new NodeSummary(null, null, null, null);
        
        assertNull(summary.getNodeId());
        assertNull(summary.getName());
        assertNull(summary.getDescription());
        assertNull(summary.getColor());
    }

    @Test
    void testNodeSummaryColorCodes() {
        NodeSummary summary1 = new NodeSummary("1", "Node1", "Desc1", "red");
        NodeSummary summary2 = new NodeSummary("2", "Node2", "Desc2", "blue");
        
        assertNotEquals(summary1.getColor(), summary2.getColor());
        assertNotEquals(summary1.getNodeId(), summary2.getNodeId());
    }

    @Test
    void testNodeSummaryUpdate() {
        NodeSummary summary = new NodeSummary("initial", "Init", "Init desc", "red");
        
        assertEquals("initial", summary.getNodeId());
        
        summary.setNodeId("updated");
        assertEquals("updated", summary.getNodeId());
    }
}
