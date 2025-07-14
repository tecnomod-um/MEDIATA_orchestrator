package org.taniwha.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeMetadata;
import org.taniwha.model.NodeSummary;
import org.taniwha.service.NodeAccessService;
import org.taniwha.service.NodeService;

import java.util.*;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NodeRetrieverControllerTest {

    private MockMvc mvc;
    private NodeService nodeService;
    private NodeAccessService nodeAccessService;

    private final String jwtHeader = "Bearer JWT123";
    private final String tgtHeader = "TGT-VAL";

    @BeforeEach
    void setup() {
        nodeService = mock(NodeService.class);
        nodeAccessService = mock(NodeAccessService.class);
        mvc = MockMvcBuilders.standaloneSetup(new NodeRetrieverController(nodeService, nodeAccessService)).build();
    }

    @Test
    void listNodes_returnsSummaries() throws Exception {
        List<NodeSummary> summaries = Arrays.asList(
                new NodeSummary("n1", "Name1", "Desc1", "Red"),
                new NodeSummary("n2", "Name2", "Desc2", "Blue")
        );
        when(nodeService.getNodeSummaries()).thenReturn(summaries);

        mvc.perform(get("/nodes/connect/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodeId").value("n1"))
                .andExpect(jsonPath("$[1].color").value("Blue"));
    }

    @Test
    void getNodeInfo_nodeNotFound_returns404() throws Exception {
        when(nodeService.findNodeById("n1")).thenReturn(null);

        mvc.perform(get("/nodes/connect/info/n1")
                        .header("Authorization", jwtHeader)
                        .header("Kerberos-TGT", tgtHeader))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Node not found"));
    }

    @Test
    void getNodeInfo_noAccess_returns412() throws Exception {
        NodeInfo info = new NodeInfo("n1", "1.2.3.4", "Name", "", "Desc", "Red", "pk");
        when(nodeService.findNodeById("n1")).thenReturn(info);
        when(nodeAccessService.checkUserAccess("n1", "JWT123")).thenReturn(false);

        mvc.perform(get("/nodes/connect/info/n1")
                        .header("Authorization", jwtHeader)
                        .header("Kerberos-TGT", tgtHeader))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error")
                        .value("You do not have the required permissions to access this node."));
    }

    @Test
    void getNodeInfo_serviceError502() throws Exception {
        NodeInfo info = new NodeInfo("n1", "1.2.3.4", "Name", "", "Desc", "Red", "pk");
        when(nodeService.findNodeById("n1")).thenReturn(info);
        when(nodeAccessService.checkUserAccess("n1", "JWT123")).thenReturn(true);

        Map<String, Object> err = new HashMap<>();
        err.put("error", "Failed to connect to the node X");
        when(nodeAccessService.getServiceToken("n1", tgtHeader)).thenReturn(err);

        mvc.perform(get("/nodes/connect/info/n1")
                        .header("Authorization", jwtHeader)
                        .header("Kerberos-TGT", tgtHeader))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error")
                        .value("Failed to connect to the node X"));
    }

    @Test
    void getNodeInfo_serviceError500() throws Exception {
        NodeInfo info = new NodeInfo("n1", "1.2.3.4", "Name", "", "Desc", "Red", "pk");
        when(nodeService.findNodeById("n1")).thenReturn(info);
        when(nodeAccessService.checkUserAccess("n1", "JWT123")).thenReturn(true);

        Map<String, Object> err = new HashMap<>();
        err.put("error", "Something else");
        when(nodeAccessService.getServiceToken("n1", tgtHeader)).thenReturn(err);

        mvc.perform(get("/nodes/connect/info/n1")
                        .header("Authorization", jwtHeader)
                        .header("Kerberos-TGT", tgtHeader))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Something else"));
    }

    @Test
    void getNodeInfo_success_returnsTokenAndNodeInfo() throws Exception {
        NodeInfo info = new NodeInfo("n1", "1.2.3.4", "Name", "", "Desc", "Red", "pk");
        when(nodeService.findNodeById("n1")).thenReturn(info);
        when(nodeAccessService.checkUserAccess("n1", "JWT123")).thenReturn(true);

        Map<String, Object> ok = new HashMap<>();
        ok.put("token", "ABC");
        when(nodeAccessService.getServiceToken("n1", tgtHeader)).thenReturn(ok);

        mvc.perform(get("/nodes/connect/info/n1")
                        .header("Authorization", jwtHeader)
                        .header("Kerberos-TGT", tgtHeader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("ABC"))
                .andExpect(jsonPath("$.nodeInfo.nodeId").value("n1"))
                .andExpect(jsonPath("$.nodeInfo.ip").value("1.2.3.4"));
    }

    @Test
    void getNodeMetadata_notFound_returns404() throws Exception {
        when(nodeAccessService.getMetadata("n1")).thenReturn(null);

        mvc.perform(get("/nodes/connect/metadata/n1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error")
                        .value("The file just doesn't exist."));
    }

    @Test
    void getNodeMetadata_success_returnsMetadata() throws Exception {
        NodeMetadata md = new NodeMetadata();
        NodeMetadata.Dataset ds = new NodeMetadata.Dataset();
        ds.setTitle("T");
        md.setDataset(Collections.singletonList(ds));
        when(nodeAccessService.getMetadata("n1")).thenReturn(md);

        mvc.perform(get("/nodes/connect/metadata/n1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.dataset[0].title").value("T"));
    }
}
