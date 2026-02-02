package org.taniwha.service;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeSummary;
import org.taniwha.model.User;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class NodeServiceTest {

    @Mock
    private NodeRepository repo;
    @Mock
    private UserRepository userRepo;
    @Mock
    private KerberosService krb;
    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder pwdEncoder;

    private NodeService svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new NodeService(repo, userRepo, krb, pwdEncoder);
        ReflectionTestUtils.setField(svc, "overwriteNode", false);
        ReflectionTestUtils.setField(svc, "realm", "REALM");
    }

    @Test
    void registerNode_happyPath() throws KrbException {
        NodeInfo in = new NodeInfo();
        in.setNodeId("n1");
        in.setIp("1.2.3.4");

        when(repo.existsByIp("1.2.3.4")).thenReturn(false);
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("1.2.3.4", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/n1.keytab");

        // Mock admin user for access grant
        User adminUser = new User(null, "admin", "pass", "admin@test.com",
                Collections.emptyList(), new ArrayList<>());
        when(userRepo.findByUsername("admin")).thenReturn(adminUser);

        String path = svc.registerNode(in);
        assertThat(path).isEqualTo("/tmp/n1.keytab");
        InOrder ord = inOrder(repo, krb);
        ord.verify(repo).save(in);
        assertThat(in.getPassword()).isEqualTo("ENC");
        verify(krb).createPrincipal(eq("p@REALM"), anyString());
        verify(krb).createKeytab("p@REALM");
        assertThat(svc.getLastHeartbeat("n1")).isNotNull();

        // Verify admin user was granted access
        verify(userRepo).findByUsername("admin");
        verify(userRepo).save(adminUser);
        assertThat(adminUser.getNodeIds()).contains(in);
    }

    @Test
    void registerNode_alreadyExists_andOverwriteFalse() {
        NodeInfo in = new NodeInfo();
        in.setIp("1.2.3.4");
        when(repo.existsByIp("1.2.3.4")).thenReturn(true);
        String res = svc.registerNode(in);
        assertThat(res).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void registerNode_alreadyExists_andOverwriteTrue() throws KrbException {
        ReflectionTestUtils.setField(svc, "overwriteNode", true);

        NodeInfo existing = new NodeInfo();
        existing.setNodeId("e1");
        existing.setIp("1.2.3.4");

        when(repo.existsByIp("1.2.3.4")).thenReturn(true);
        when(repo.findByIp("1.2.3.4")).thenReturn(existing);
        when(repo.findById("e1")).thenReturn(Optional.of(existing));

        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("1.2.3.4", "REALM")).thenReturn("p@REALM");
        doNothing().when(krb).deletePrincipal("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("KT");

        String out = svc.registerNode(new NodeInfo() {{
            setNodeId("new");
            setIp("1.2.3.4");
        }});
        verify(repo).deleteById("e1");
        verify(krb).deletePrincipal("p@REALM");
        verify(repo).save(any());
        assertThat(out).isEqualTo("KT");
    }

    @Test
    void deregisterNode_whenExists() throws KrbException {
        NodeInfo n = new NodeInfo();
        n.setNodeId("foo");
        n.setIp("5.6.7.8");

        when(repo.findById("foo")).thenReturn(Optional.of(n));
        when(krb.getPrincipalName("5.6.7.8", "REALM"))
                .thenReturn("5.6.7.8@REALM");
        doNothing().when(krb).deletePrincipal("5.6.7.8@REALM");
        svc.updateHeartbeat("foo", Instant.EPOCH);
        svc.deregisterNode("foo");

        verify(repo).deleteById("foo");
        assertThat(svc.getLastHeartbeat("foo")).isNull();
        verify(krb).deletePrincipal("5.6.7.8@REALM");
    }

    @Test
    void deregisterNode_whenMissing() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        svc.deregisterNode("nope");
        verify(repo, never()).deleteById(any());
    }

    @Test
    void otherGetters() {
        when(repo.existsById("i")).thenReturn(true);
        assertThat(svc.nodeIsNotRegistered("i")).isFalse();
        assertThat(svc.nodeIsNotRegistered("x")).isTrue();

        NodeInfo a = new NodeInfo();
        a.setNodeId("A");
        a.setName("Name");
        a.setDescription("Desc");
        a.setColor("C");
        when(repo.findAll()).thenReturn(Collections.singletonList(a));
        assertThat(svc.getActiveNodes()).containsExactly(a);

        List<NodeSummary> sums = svc.getNodeSummaries();
        assertThat(sums).hasSize(1)
                .first()
                .extracting(
                        NodeSummary::getNodeId,
                        NodeSummary::getName,
                        NodeSummary::getDescription,
                        NodeSummary::getColor
                )
                .containsExactly("A", "Name", "Desc", "C");
    }

    @Test
    void registerNode_grantsAdminAccess_whenAdminUserExists() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("test-node");
        node.setIp("10.0.0.1");
        node.setName("Test Node");

        User adminUser = new User(null, "admin", "pass", "admin@test.com",
                Collections.emptyList(), new ArrayList<>());

        when(repo.existsByIp("10.0.0.1")).thenReturn(false);
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");
        when(userRepo.findByUsername("admin")).thenReturn(adminUser);

        svc.registerNode(node);

        verify(userRepo).findByUsername("admin");
        verify(userRepo).save(adminUser);
        assertThat(adminUser.getNodeIds()).hasSize(1);
        assertThat(adminUser.getNodeIds().get(0).getNodeId()).isEqualTo("test-node");
    }

    @Test
    void registerNode_doesNotFail_whenAdminUserNotFound() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("test-node");
        node.setIp("10.0.0.1");

        when(repo.existsByIp("10.0.0.1")).thenReturn(false);
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");
        when(userRepo.findByUsername("admin")).thenReturn(null);

        String result = svc.registerNode(node);

        assertThat(result).isEqualTo("/tmp/test.keytab");
        verify(userRepo).findByUsername("admin");
        verify(userRepo, never()).save(any());
    }

    @Test
    void registerNode_doesNotAddDuplicateNodeAccess() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("test-node");
        node.setIp("10.0.0.1");

        NodeInfo existingNode = new NodeInfo();
        existingNode.setNodeId("test-node");

        List<NodeInfo> nodeAccess = new ArrayList<>();
        nodeAccess.add(existingNode);

        User adminUser = new User(null, "admin", "pass", "admin@test.com",
                Collections.emptyList(), nodeAccess);

        when(repo.existsByIp("10.0.0.1")).thenReturn(false);
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");
        when(userRepo.findByUsername("admin")).thenReturn(adminUser);

        svc.registerNode(node);

        verify(userRepo).findByUsername("admin");
        // Should not save if node already in access list
        verify(userRepo, never()).save(any());
        assertThat(adminUser.getNodeIds()).hasSize(1);
    }

    @Test
    void registerNode_continuesOnAccessGrantError() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("test-node");
        node.setIp("10.0.0.1");

        when(repo.existsByIp("10.0.0.1")).thenReturn(false);
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");
        when(userRepo.findByUsername("admin")).thenThrow(new RuntimeException("Database error"));

        // Should not fail node registration
        String result = svc.registerNode(node);

        assertThat(result).isEqualTo("/tmp/test.keytab");
        verify(repo).save(node);
    }
}
