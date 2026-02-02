package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeHeartbeatTest {

    @Test
    void testNodeHeartbeatGettersAndSetters() {
        NodeHeartbeat heartbeat = new NodeHeartbeat();

        heartbeat.setNodeId("node1");
        heartbeat.setTimestamp(1234567890L);

        assertEquals("node1", heartbeat.getNodeId());
        assertEquals(1234567890L, heartbeat.getTimestamp());
    }

    @Test
    void testNodeHeartbeatNullValues() {
        NodeHeartbeat heartbeat = new NodeHeartbeat();

        assertNull(heartbeat.getNodeId());
        assertEquals(0L, heartbeat.getTimestamp());
    }

    @Test
    void testNodeHeartbeatTimestampUpdate() {
        NodeHeartbeat heartbeat = new NodeHeartbeat();

        long timestamp1 = System.currentTimeMillis();
        heartbeat.setTimestamp(timestamp1);
        assertEquals(timestamp1, heartbeat.getTimestamp());

        long timestamp2 = System.currentTimeMillis() + 1000;
        heartbeat.setTimestamp(timestamp2);
        assertEquals(timestamp2, heartbeat.getTimestamp());
    }

    @Test
    void testNodeHeartbeatMultipleNodes() {
        NodeHeartbeat heartbeat1 = new NodeHeartbeat();
        heartbeat1.setNodeId("node1");
        heartbeat1.setTimestamp(1000L);

        NodeHeartbeat heartbeat2 = new NodeHeartbeat();
        heartbeat2.setNodeId("node2");
        heartbeat2.setTimestamp(2000L);

        assertNotEquals(heartbeat1.getNodeId(), heartbeat2.getNodeId());
        assertNotEquals(heartbeat1.getTimestamp(), heartbeat2.getTimestamp());
    }
}
