package org.taniwha.controller;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.SecurePoolRosterRequest;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeAccessService;
import org.taniwha.service.NodeService;
import org.taniwha.util.JwtTokenUtil;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurePoolRosterControllerTest {
    private final NodeService nodes=mock(NodeService.class);
    private final NodeAccessService access=mock(NodeAccessService.class);
    private final JwtTokenUtil tokens=mock(JwtTokenUtil.class);
    private final SecurePoolRosterController controller=new SecurePoolRosterController(nodes,access,tokens);

    @Test void userMustHaveAccessToEveryDistinctActiveParticipant() {
        node("a","key-a");node("b","key-b");
        when(access.checkUserAccess("a","user")).thenReturn(true);
        assertThatThrownBy(()->controller.participants(List.of("a","b"),"Bearer user")).isInstanceOf(ResponseStatusException.class);
        when(access.checkUserAccess("b","user")).thenReturn(true);
        var result=controller.participants(List.of("b","a"),"Bearer user").getBody();
        assertThat(result).extracting("nodeId").containsExactly("a","b");
        assertThatThrownBy(()->controller.participants(List.of("a","a"),"Bearer user")).isInstanceOf(ResponseStatusException.class);
        node("b","key-a");
        assertThatThrownBy(()->controller.participants(List.of("a","b"),"Bearer user")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void nodeLookupRequiresAnEndpointScopedRegisteredNodeSignature() {
        node("a","key-a");node("b","key-b");
        var request=new SecurePoolRosterRequest("a",List.of("a","b"));
        assertThatThrownBy(()->controller.nodeRoster(request,"Bearer invalid")).isInstanceOf(ResponseStatusException.class);
        when(tokens.validateNodeSyncToken("signed","a","/nodes/secure-pool/roster","key-a")).thenReturn(true);
        assertThat(controller.nodeRoster(request,"Bearer signed").getBody()).hasSize(2);
        verifyNoInteractions(access);
        var peer=nodes.findNodeById("b");peer.setActive(false);
        assertThatThrownBy(()->controller.nodeRoster(request,"Bearer signed")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void expectedValidationErrorsKeepTheirStatusWithTheGlobalErrorHandler() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new org.taniwha.exception.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/nodes/secure-pool/participants")
                        .header("Authorization","Bearer user").contentType("application/json").content("[\"a\"]"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/nodes/secure-pool/roster")
                        .contentType("application/json").content("{\"nodeId\":\"a\",\"participantIds\":[\"a\",\"b\"]}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }

    private void node(String id,String key) {
        var node=new NodeInfo(id,"https://"+id,"Site",null,null,null,key);node.setActive(true);
        when(nodes.findNodeById(id)).thenReturn(node);
    }
}
