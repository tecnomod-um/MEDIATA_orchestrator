package org.taniwha.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeProvenanceActivity;
import org.taniwha.model.NodeProvenanceBatch;
import org.taniwha.service.NodeService;
import org.taniwha.service.ProjectProvenanceService;
import org.taniwha.util.JwtTokenUtil;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NodeProvenanceControllerTest {

    private MockMvc mvc;
    private NodeService nodeService;
    private ProjectProvenanceService provenanceService;
    private JwtTokenUtil jwtTokenUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        nodeService = mock(NodeService.class);
        provenanceService = mock(ProjectProvenanceService.class);
        jwtTokenUtil = mock(JwtTokenUtil.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new NodeProvenanceController(nodeService, provenanceService, jwtTokenUtil)).build();
    }

    @Test
    void repeatedBatchAcknowledgesAlreadyPersistedEvent() throws Exception {
        NodeProvenanceBatch batch = batch();
        NodeInfo node = new NodeInfo();
        node.setNodeId("node-1");
        node.setName("Clinical node");
        node.setPublicKey("node-public-key");
        when(jwtTokenUtil.validateNodeSyncToken(
                "node-token", "node-1", "/nodes/provenance/batch", "node-public-key"))
                .thenReturn(true);
        when(nodeService.findNodeById("node-1")).thenReturn(node);
        when(provenanceService.ingestNodeProvenance(
                org.mockito.ArgumentMatchers.eq("node-1"),
                org.mockito.ArgumentMatchers.eq("Clinical node"),
                anyList()))
                .thenReturn(List.of("event-1"));

        mvc.perform(post("/nodes/provenance/batch")
                        .header("Authorization", "Bearer node-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedEventIds[0]").value("event-1"));
    }

    @Test
    void invalidNodeTokenIsRejectedBeforeStorage() throws Exception {
        NodeInfo node = new NodeInfo();
        node.setNodeId("node-1");
        node.setPublicKey("node-public-key");
        when(nodeService.findNodeById("node-1")).thenReturn(node);

        mvc.perform(post("/nodes/provenance/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch())))
                .andExpect(status().isUnauthorized());

        verify(provenanceService, never()).ingestNodeProvenance(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                anyList());
    }

    private NodeProvenanceBatch batch() {
        NodeProvenanceActivity activity = new NodeProvenanceActivity();
        activity.setActivityId("event-1");
        activity.setOutputDatasetId("parsed_source.csv");
        activity.setSourceDatasetIds(List.of("source.csv"));
        activity.setMappingSpecId("a".repeat(64));
        activity.setGeneratedAt("2026-10-19T11:01:00Z");

        NodeProvenanceBatch batch = new NodeProvenanceBatch();
        batch.setNodeId("node-1");
        batch.setSchemaVersion(1);
        batch.setActivities(List.of(activity));
        return batch;
    }
}
