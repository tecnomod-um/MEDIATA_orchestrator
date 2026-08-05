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
import org.taniwha.model.Project;
import org.taniwha.model.User;
import org.taniwha.repository.NodeRepository;
import org.taniwha.repository.ProjectRepository;
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
    private ProjectRepository projectRepo;
    @Mock
    private KerberosService krb;
    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder pwdEncoder;

    private NodeService svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new NodeService(repo, userRepo, projectRepo, krb, pwdEncoder);
        ReflectionTestUtils.setField(svc, "overwriteNode", false);
        ReflectionTestUtils.setField(svc, "nodeReclaimWindowMinutes", 60L);
        ReflectionTestUtils.setField(svc, "realm", "REALM");
    }

    @Test
    void registerNode_happyPath() throws KrbException {
        NodeInfo in = new NodeInfo();
        in.setNodeId("n1");
        in.setIp("1.2.3.4");

        when(repo.findAllByIp("1.2.3.4")).thenReturn(Collections.emptyList());
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("1.2.3.4", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/n1.keytab");

        User adminUser = new User(null, "admin", "pass", "admin@test.com",
                Collections.emptyList(), new ArrayList<>());
        when(userRepo.findByUsername("admin")).thenReturn(adminUser);

        String path = svc.registerNode(in);
        assertThat(path).isEqualTo("/tmp/n1.keytab");
        InOrder ord = inOrder(repo, krb);
        ord.verify(repo).save(in);
        assertThat(in.getActive()).isTrue();
        assertThat(in.getDeregisteredAt()).isNull();
        assertThat(in.getPassword()).isEqualTo("ENC");
        verify(krb).createPrincipal(eq("p@REALM"), anyString());
        verify(krb).createKeytab("p@REALM");
        assertThat(svc.getLastHeartbeat("n1")).isNotNull();

        verify(userRepo).findByUsername("admin");
        verify(userRepo).save(adminUser);
        assertThat(adminUser.getNodeIds()).contains(in);
        verify(projectRepo).save(argThat(project ->
                "STRATIF-AI".equals(project.getName()) && project.getNodeIds().contains("n1")));
    }

    @Test
    void registerNode_addsNodeToExistingStratifProject() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("new-node");
        node.setIp("10.0.0.1");

        Project stratif = new Project();
        stratif.setName("STRATIF-AI");
        stratif.setNodeIds(new ArrayList<>(List.of("existing-node")));

        when(projectRepo.findByName("STRATIF-AI")).thenReturn(stratif);
        when(repo.findAllByIp("10.0.0.1")).thenReturn(Collections.emptyList());
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");

        String result = svc.registerNode(node);

        assertThat(result).isEqualTo("/tmp/test.keytab");
        assertThat(stratif.getNodeIds()).containsExactly("existing-node", "new-node");
        verify(projectRepo).save(stratif);
    }

    @Test
    void registerNode_doesNotAddDefaultProjectNodeToStratifProject() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("default-node");
        node.setIp("10.0.0.2");

        Project defaultProject = new Project();
        defaultProject.setName("Default Project");
        defaultProject.setNodeIds(new ArrayList<>(List.of("default-node")));

        when(projectRepo.findByName("Default Project")).thenReturn(defaultProject);
        when(repo.findAllByIp("10.0.0.2")).thenReturn(Collections.emptyList());
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.2", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");

        String result = svc.registerNode(node);

        assertThat(result).isEqualTo("/tmp/test.keytab");
        verify(projectRepo, never()).save(any(Project.class));
    }

    @Test
    void registerNode_assignsDefaultLocalNodeToDefaultProjectAndRemovesFromStratif() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("new-default-id");
        node.setIp("http://mediata-default-node.local:18083");
        node.setName("Default Local");

        Project defaultProject = new Project();
        defaultProject.setName("Default Project");
        defaultProject.setNodeIds(new ArrayList<>());

        Project stratifProject = new Project();
        stratifProject.setName("STRATIF-AI");
        stratifProject.setNodeIds(new ArrayList<>(List.of("remote-node", "new-default-id")));

        when(projectRepo.findByName("Default Project")).thenReturn(defaultProject);
        when(projectRepo.findByName("STRATIF-AI")).thenReturn(stratifProject);
        when(repo.findAllByIp("http://mediata-default-node.local:18083")).thenReturn(Collections.emptyList());
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("http://mediata-default-node.local:18083", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");

        String result = svc.registerNode(node);

        assertThat(result).isEqualTo("/tmp/test.keytab");
        assertThat(defaultProject.getNodeIds()).containsExactly("new-default-id");
        assertThat(stratifProject.getNodeIds()).containsExactly("remote-node");
        verify(projectRepo).save(defaultProject);
        verify(projectRepo).save(stratifProject);
    }

    @Test
    void registerNode_alreadyExists_andOverwriteFalse() {
        NodeInfo in = new NodeInfo();
        in.setIp("1.2.3.4");
        NodeInfo existing = new NodeInfo();
        existing.setIp("1.2.3.4");
        when(repo.findAllByIp("1.2.3.4")).thenReturn(Collections.singletonList(existing));
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

        when(repo.findAllByIp("1.2.3.4")).thenReturn(Collections.singletonList(existing));

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
    void registerNode_reclaimsRecentInactiveNodeWithSameNameAndIp() throws KrbException {
        NodeInfo tombstone = new NodeInfo();
        tombstone.setNodeId("old-id");
        tombstone.setIp("1.2.3.4");
        tombstone.setName("scuba");
        tombstone.setActive(false);
        tombstone.setDeregisteredAt(Instant.now().minusSeconds(120));

        NodeInfo incoming = new NodeInfo();
        incoming.setNodeId("new-id");
        incoming.setIp("1.2.3.4");
        incoming.setName("scuba");

        when(repo.findAllByIp("1.2.3.4")).thenReturn(Collections.singletonList(tombstone));
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("1.2.3.4", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("KT");

        String out = svc.registerNode(incoming);

        assertThat(out).isEqualTo("KT");
        assertThat(incoming.getNodeId()).isEqualTo("old-id");
        assertThat(incoming.getActive()).isTrue();
        assertThat(incoming.getDeregisteredAt()).isNull();
        verify(repo, never()).deleteById("old-id");
        verify(repo).save(incoming);
    }

    @Test
    void registerNode_doesNotReclaimExpiredInactiveNode() throws KrbException {
        NodeInfo tombstone = new NodeInfo();
        tombstone.setNodeId("old-id");
        tombstone.setIp("1.2.3.4");
        tombstone.setName("scuba");
        tombstone.setActive(false);
        tombstone.setDeregisteredAt(Instant.now().minusSeconds(7200));

        NodeInfo incoming = new NodeInfo();
        incoming.setNodeId("new-id");
        incoming.setIp("1.2.3.4");
        incoming.setName("scuba");

        when(repo.findAllByIp("1.2.3.4")).thenReturn(Collections.singletonList(tombstone));
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("1.2.3.4", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("KT");

        String out = svc.registerNode(incoming);

        assertThat(out).isEqualTo("KT");
        assertThat(incoming.getNodeId()).isEqualTo("new-id");
        assertThat(incoming.getActive()).isTrue();
        verify(repo).deleteById("old-id");
        verify(repo).save(incoming);
    }

    @Test
    void deregisterNode_whenExists() throws KrbException {
        NodeInfo n = new NodeInfo();
        n.setNodeId("foo");
        n.setIp("5.6.7.8");
        Project defaultProject = new Project();
        defaultProject.setName("Default Project");
        defaultProject.setNodeIds(new ArrayList<>(List.of("foo", "default-peer")));
        Project stratifProject = new Project();
        stratifProject.setName("STRATIF-AI");
        stratifProject.setNodeIds(new ArrayList<>(List.of("remote-peer", "foo")));

        when(repo.findById("foo")).thenReturn(Optional.of(n));
        when(projectRepo.findAll()).thenReturn(List.of(defaultProject, stratifProject));
        when(krb.getPrincipalName("5.6.7.8", "REALM"))
                .thenReturn("5.6.7.8@REALM");
        doNothing().when(krb).deletePrincipal("5.6.7.8@REALM");
        svc.updateHeartbeat("foo", Instant.EPOCH);
        svc.deregisterNode("foo");

        verify(repo).save(n);
        assertThat(n.getActive()).isFalse();
        assertThat(n.getDeregisteredAt()).isNotNull();
        assertThat(svc.getLastHeartbeat("foo")).isNull();
        verify(krb).deletePrincipal("5.6.7.8@REALM");
        assertThat(defaultProject.getNodeIds()).containsExactly("default-peer");
        assertThat(stratifProject.getNodeIds()).containsExactly("remote-peer");
        verify(projectRepo).save(defaultProject);
        verify(projectRepo).save(stratifProject);
    }

    @Test
    void deregisterNode_whenMissing() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        svc.deregisterNode("nope");
        verify(repo, never()).deleteById(any());
        verify(repo, never()).save(any());
    }

    @Test
    void otherGetters() {
        NodeInfo registered = new NodeInfo();
        registered.setNodeId("i");
        registered.setActive(true);
        NodeInfo inactiveRegistration = new NodeInfo();
        inactiveRegistration.setNodeId("inactive");
        inactiveRegistration.setActive(false);
        when(repo.findById("i")).thenReturn(Optional.of(registered));
        when(repo.findById("inactive")).thenReturn(Optional.of(inactiveRegistration));
        when(repo.findById("x")).thenReturn(Optional.empty());
        assertThat(svc.nodeIsNotRegistered("i")).isFalse();
        assertThat(svc.nodeIsNotRegistered("inactive")).isTrue();
        assertThat(svc.nodeIsNotRegistered("x")).isTrue();

        NodeInfo a = new NodeInfo();
        a.setNodeId("A");
        a.setName("Name");
        a.setDescription("Desc");
        a.setColor("C");
        a.setIp("https://alpha.example/taniwha");
        NodeInfo inactive = new NodeInfo();
        inactive.setNodeId("B");
        inactive.setActive(false);
        inactive.setIp("https://beta.example/taniwha");
        when(repo.findAll()).thenReturn(List.of(a, inactive));
        assertThat(svc.getActiveNodes()).containsExactly(a);
        assertThat(svc.findNodeById("i")).isSameAs(registered);
        assertThat(svc.findNodeById("inactive")).isNull();

        List<NodeSummary> sums = svc.getNodeSummaries();
        assertThat(sums).hasSize(1)
                .first()
                .extracting(
                        NodeSummary::getNodeId,
                        NodeSummary::getName,
                        NodeSummary::getDescription,
                        NodeSummary::getColor,
                        NodeSummary::getServiceUrl
                )
                .containsExactly("A", "Name", "Desc", "C", "https://alpha.example/taniwha");
    }

    @Test
    void registerNode_grantsAdminAccess_whenAdminUserExists() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("test-node");
        node.setIp("10.0.0.1");
        node.setName("Test Node");

        User adminUser = new User(null, "admin", "pass", "admin@test.com",
                Collections.emptyList(), new ArrayList<>());

        when(repo.findAllByIp("10.0.0.1")).thenReturn(Collections.emptyList());
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

        when(repo.findAllByIp("10.0.0.1")).thenReturn(Collections.emptyList());
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

        when(repo.findAllByIp("10.0.0.1")).thenReturn(Collections.emptyList());
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");
        when(userRepo.findByUsername("admin")).thenReturn(adminUser);

        svc.registerNode(node);

        verify(userRepo).findByUsername("admin");
        verify(userRepo, never()).save(any());
        assertThat(adminUser.getNodeIds()).hasSize(1);
    }

    @Test
    void registerNode_continuesOnAccessGrantError() throws KrbException {
        NodeInfo node = new NodeInfo();
        node.setNodeId("test-node");
        node.setIp("10.0.0.1");

        when(repo.findAllByIp("10.0.0.1")).thenReturn(Collections.emptyList());
        when(pwdEncoder.encode(anyString())).thenReturn("ENC");
        when(krb.getPrincipalName("10.0.0.1", "REALM")).thenReturn("p@REALM");
        when(krb.createKeytab("p@REALM")).thenReturn("/tmp/test.keytab");
        when(userRepo.findByUsername("admin")).thenThrow(new RuntimeException("Database error"));

        String result = svc.registerNode(node);

        assertThat(result).isEqualTo("/tmp/test.keytab");
        verify(repo).save(node);
    }
}
