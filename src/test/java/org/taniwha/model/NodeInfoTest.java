package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeInfoTest {

    @Test
    void testNodeInfoNoArgsConstructor() {
        NodeInfo node = new NodeInfo();
        assertNotNull(node);
        assertNull(node.getNodeId());
        assertNull(node.getIp());
        assertNull(node.getName());
    }

    @Test
    void testNodeInfoAllArgsConstructor() {
        NodeInfo node = new NodeInfo("node1", "192.168.1.1", "TestNode", 
                                      "password123", "Test description", 
                                      "blue", "publicKey123");
        
        assertEquals("node1", node.getNodeId());
        assertEquals("192.168.1.1", node.getIp());
        assertEquals("TestNode", node.getName());
        assertEquals("password123", node.getPassword());
        assertEquals("Test description", node.getDescription());
        assertEquals("blue", node.getColor());
        assertEquals("publicKey123", node.getPublicKey());
    }

    @Test
    void testNodeInfoSetters() {
        NodeInfo node = new NodeInfo();
        
        node.setNodeId("node2");
        node.setIp("10.0.0.1");
        node.setName("MyNode");
        node.setPassword("secret");
        node.setDescription("A test node");
        node.setColor("red");
        node.setPublicKey("key456");
        
        assertEquals("node2", node.getNodeId());
        assertEquals("10.0.0.1", node.getIp());
        assertEquals("MyNode", node.getName());
        assertEquals("secret", node.getPassword());
        assertEquals("A test node", node.getDescription());
        assertEquals("red", node.getColor());
        assertEquals("key456", node.getPublicKey());
    }

    @Test
    void testGetServiceUrl() {
        NodeInfo node = new NodeInfo();
        node.setIp("http://example.com:8080");
        
        assertEquals("http://example.com:8080", node.getServiceUrl());
    }

    @Test
    void testToString() {
        NodeInfo node = new NodeInfo("node1", "192.168.1.1", "TestNode", 
                                      "password123", "Test description", 
                                      "blue", "publicKey123");
        
        String toString = node.toString();
        
        assertTrue(toString.contains("node1"));
        assertTrue(toString.contains("192.168.1.1"));
        assertTrue(toString.contains("TestNode"));
        assertTrue(toString.contains("Test description"));
        assertTrue(toString.contains("blue"));
        assertTrue(toString.contains("publicKey123"));
    }

    @Test
    void testToStringWithNullValues() {
        NodeInfo node = new NodeInfo();
        String toString = node.toString();
        
        assertNotNull(toString);
        assertTrue(toString.contains("NodeInfo"));
    }

    @Test
    void testNodeInfoEquality() {
        NodeInfo node1 = new NodeInfo("node1", "192.168.1.1", "TestNode", 
                                       "password123", "Test description", 
                                       "blue", "publicKey123");
        NodeInfo node2 = new NodeInfo("node1", "192.168.1.1", "TestNode", 
                                       "password123", "Test description", 
                                       "blue", "publicKey123");
        
        // Test that we can create two nodes with same values
        assertEquals(node1.getNodeId(), node2.getNodeId());
        assertEquals(node1.getIp(), node2.getIp());
        assertEquals(node1.getName(), node2.getName());
    }
}
