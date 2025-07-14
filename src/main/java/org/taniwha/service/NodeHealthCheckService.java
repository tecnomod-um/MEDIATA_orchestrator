package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.taniwha.model.NodeInfo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Keeps track of alive nodes in the system
@Service
public class NodeHealthCheckService {

    private static final Logger logger = LoggerFactory.getLogger(NodeHealthCheckService.class);

    private final NodeService nodeService;
    private final long heartbeatTimeoutMinutes;

    public NodeHealthCheckService(NodeService nodeService, @Value("${heartbeat.timeout.minutes}") long heartbeatTimeoutMinutes) {
        this.nodeService = nodeService;
        this.heartbeatTimeoutMinutes = heartbeatTimeoutMinutes;
    }

    @Scheduled(fixedRateString = "${health.check.interval.ms}")
    public void checkNodeHealth() {
        Instant now = Instant.now();
        Iterable<NodeInfo> nodes = nodeService.getActiveNodes();

        for (NodeInfo node : nodes) {
            Instant lastHeartbeat = nodeService.getLastHeartbeat(node.getNodeId());
            // Initialize previous registered nodes on boot
            if (lastHeartbeat == null) {
                nodeService.updateHeartbeat(node.getNodeId(), now);
                logger.debug("Initialized heartbeat for node {} at {}", node.getNodeId(), now);
            } else if (ChronoUnit.MINUTES.between(lastHeartbeat, now) > heartbeatTimeoutMinutes) {

                nodeService.deregisterNode(node.getNodeId());
                logger.warn("Deregistered node {} due to heartbeat timeout.", node.getNodeId());

            }
        }
    }
}
