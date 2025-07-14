package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.taniwha.model.NodeInfo;

import java.time.Instant;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NodeHealthCheckServiceTest {

    @Mock
    private NodeService nodeService;

    private NodeHealthCheckService healthCheck;
    private final long timeout = 5;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        healthCheck = new NodeHealthCheckService(nodeService, timeout);
    }

    @Test
    void checkNodeHealth_initializesHeartbeat_whenLastHeartbeatNull() {
        NodeInfo node = new NodeInfo();
        node.setNodeId("node1");

        when(nodeService.getActiveNodes()).thenReturn(Collections.singletonList(node));
        when(nodeService.getLastHeartbeat("node1")).thenReturn(null);
        healthCheck.checkNodeHealth();
        verify(nodeService).updateHeartbeat(eq("node1"), any(Instant.class));
        verify(nodeService, never()).deregisterNode(any());
    }

    @Test
    void checkNodeHealth_deregisters_whenTimeoutExceeded() {
        NodeInfo node = new NodeInfo();
        node.setNodeId("node1");

        Instant tooOld = Instant.now()
                .minus(timeout + 1, java.time.temporal.ChronoUnit.MINUTES);

        when(nodeService.getActiveNodes()).thenReturn(Collections.singletonList(node));
        when(nodeService.getLastHeartbeat("node1")).thenReturn(tooOld);
        healthCheck.checkNodeHealth();

        verify(nodeService).deregisterNode("node1");
        verify(nodeService, never()).updateHeartbeat(any(), any());
    }

    @Test
    void checkNodeHealth_noop_whenWithinTimeout() {
        NodeInfo node = new NodeInfo();
        node.setNodeId("node1");

        Instant fresh = Instant.now()
                .minus(timeout - 1, java.time.temporal.ChronoUnit.MINUTES);

        when(nodeService.getActiveNodes())
                .thenReturn(Collections.singletonList(node));
        when(nodeService.getLastHeartbeat("node1"))
                .thenReturn(fresh);

        healthCheck.checkNodeHealth();

        verify(nodeService, never()).deregisterNode(any());
        verify(nodeService, never()).updateHeartbeat(any(), any());
    }
}
