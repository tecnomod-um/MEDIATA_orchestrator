package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import org.taniwha.model.NodeInfo;
import org.taniwha.model.NodeProvenanceActivity;
import org.taniwha.model.NodeProvenanceEvent;
import org.taniwha.model.Project;
import org.taniwha.model.ProjectProvenanceRecord;
import org.taniwha.model.User;
import org.taniwha.repository.NodeProvenanceEventRepository;
import org.taniwha.repository.ProjectProvenanceRepository;
import org.taniwha.repository.ProjectRepository;
import org.taniwha.repository.UserRepository;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectProvenanceServiceTest {

    @Mock
    private ProjectProvenanceRepository provenanceRepository;
    @Mock
    private NodeProvenanceEventRepository nodeEventRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private UserRepository userRepository;

    private ProjectProvenanceService service;
    private Authentication memberAuthentication;
    private Authentication adminAuthentication;
    private Project project;

    @BeforeEach
    void setUp() {
        service = new ProjectProvenanceService(
                provenanceRepository, nodeEventRepository, projectRepository, userRepository);
        memberAuthentication = new UsernamePasswordAuthenticationToken(
                "member", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        adminAuthentication = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        project = new Project();
        project.setId("project-1");
        project.setName("Stroke project");
        project.setNodeIds(List.of("node-1"));
    }

    @Test
    void nodeProvenanceIsStoredOnceWithoutClinicalOrMappingContent() {
        when(projectRepository.findByNodeIdsContaining("node-1")).thenReturn(List.of(project));
        when(nodeEventRepository.findByNodeIdAndSourceEventId("node-1", "node-activity-1"))
                .thenReturn(Optional.empty());
        when(nodeEventRepository.insert(any(NodeProvenanceEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(provenanceRepository.existsByProjectIdAndNodeIdAndSourceEventId(
                "project-1", "node-1", "node-activity-1")).thenReturn(false);
        when(provenanceRepository.save(any(ProjectProvenanceRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<String> acknowledged = service.ingestNodeProvenance(
                "node-1", "Clinical node", List.of(activity()));

        assertThat(acknowledged).containsExactly("node-activity-1");
        ArgumentCaptor<ProjectProvenanceRecord> captor =
                ArgumentCaptor.forClass(ProjectProvenanceRecord.class);
        verify(provenanceRepository).save(captor.capture());
        ProjectProvenanceRecord stored = captor.getValue();
        assertThat(stored.getProjectId()).isEqualTo("project-1");
        assertThat(stored.getActivityId()).isEqualTo("node-activity-1");
        assertThat(stored.getSourceEventId()).isEqualTo("node-activity-1");
        assertThat(stored.getSourceDatasetIds()).containsExactly("source.csv");
        assertThat(stored.getGeneratedDatasetIds()).containsExactly("parsed_source.csv");
        assertThat(stored.getMappingSpecId()).isEqualTo("a".repeat(64));
        assertThat(stored.getActorType()).isEqualTo(ProjectProvenanceRecord.ActorType.NODE);
        assertThat(stored.getOutcome()).isEqualTo(ProjectProvenanceRecord.Outcome.SUCCEEDED);
        assertThat(stored.getMessage()).contains("node membership at ingestion");
    }

    @Test
    void repeatedNodeBatchDoesNotDuplicateNodeActivity() {
        when(projectRepository.findByNodeIdsContaining("node-1")).thenReturn(List.of(project));
        when(nodeEventRepository.findByNodeIdAndSourceEventId("node-1", "node-activity-1"))
                .thenReturn(Optional.of(storedNodeEvent()));
        when(provenanceRepository.existsByProjectIdAndNodeIdAndSourceEventId(
                "project-1", "node-1", "node-activity-1")).thenReturn(true);

        List<String> acknowledged = service.ingestNodeProvenance(
                "node-1", "Clinical node", List.of(activity()));

        assertThat(acknowledged).containsExactly("node-activity-1");
        verify(provenanceRepository, never()).save(any());
        verify(nodeEventRepository, never()).insert(any(NodeProvenanceEvent.class));
    }

    @Test
    void nodeEventIsDurablyAcknowledgedEvenBeforeProjectAssignment() {
        when(projectRepository.findByNodeIdsContaining("node-1")).thenReturn(List.of());
        when(nodeEventRepository.findByNodeIdAndSourceEventId("node-1", "node-activity-1"))
                .thenReturn(Optional.empty());
        when(nodeEventRepository.insert(any(NodeProvenanceEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<String> acknowledged = service.ingestNodeProvenance(
                "node-1", "Clinical node", List.of(activity()));

        assertThat(acknowledged).containsExactly("node-activity-1");
        verify(nodeEventRepository).insert(any(NodeProvenanceEvent.class));
        verify(provenanceRepository, never()).save(any());
    }

    @Test
    void reusedNodeEventIdWithDifferentPayloadIsNotAcknowledged() {
        NodeProvenanceEvent existing = storedNodeEvent();
        existing.setOutputDatasetId("different.csv");
        when(projectRepository.findByNodeIdsContaining("node-1")).thenReturn(List.of(project));
        when(nodeEventRepository.findByNodeIdAndSourceEventId("node-1", "node-activity-1"))
                .thenReturn(Optional.of(existing));

        List<String> acknowledged = service.ingestNodeProvenance(
                "node-1", "Clinical node", List.of(activity()));

        assertThat(acknowledged).isEmpty();
        verify(provenanceRepository, never()).save(any());
    }

    @Test
    void accessWithoutProjectIsJournalledForEveryAccessibleNodeProject() {
        NodeInfo node = new NodeInfo();
        node.setNodeId("node-1");
        User member = new User("user-1", "member", null, null,
                Collections.emptyList(), List.of(node));
        Project second = new Project();
        second.setId("project-2");
        second.setNodeIds(List.of("node-1"));
        when(projectRepository.findByNodeIdsContaining("node-1")).thenReturn(List.of(project, second));
        when(projectRepository.findById("project-1")).thenReturn(Optional.of(project));
        when(projectRepository.findById("project-2")).thenReturn(Optional.of(second));
        when(userRepository.findByUsername("member")).thenReturn(member);

        service.recordUserNodeAccess(null, "node-1", "Clinical node", memberAuthentication);

        verify(provenanceRepository, org.mockito.Mockito.times(2)).save(any(ProjectProvenanceRecord.class));
    }

    @Test
    void activityJournalRequiresAdministrator() {
        assertThatThrownBy(() -> service.listActivity("project-1", 0, 50, memberAuthentication))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void provOExportContainsLineageButNotUserIdentity() throws Exception {
        NodeInfo node = new NodeInfo();
        node.setNodeId("node-1");
        User member = new User("user-1", "member", null, null,
                Collections.emptyList(), List.of(node));
        when(projectRepository.findById("project-1")).thenReturn(Optional.of(project));
        when(userRepository.findByUsername("member")).thenReturn(member);
        when(provenanceRepository.findByProjectIdOrderByOccurredAtAsc("project-1"))
                .thenReturn(List.of(storedRecord()));

        Map<String, Object> export = service.exportProvO("project-1", memberAuthentication);
        String json = new ObjectMapper().writeValueAsString(export);

        assertThat(json).contains("prov:Bundle", "prov:wasGeneratedBy", "prov:wasDerivedFrom",
                "prov:wasAttributedTo", "prov:used", "prov:hadPlan",
                "prov:generatedAtTime", "Clinical node",
                "parsed_source.csv", "a".repeat(64));
        assertThat(json).doesNotContain("member");
    }

    @Test
    void adminCanReadProjectProvenanceWithoutNodeMembership() {
        when(projectRepository.findById("project-1")).thenReturn(Optional.of(project));
        when(provenanceRepository.findByProjectIdOrderByOccurredAtAsc("project-1"))
                .thenReturn(List.of());

        Map<String, Object> export = service.exportProvO("project-1", adminAuthentication);

        assertThat(export.get("@type")).isEqualTo("prov:Bundle");
    }

    private NodeProvenanceActivity activity() {
        NodeProvenanceActivity activity = new NodeProvenanceActivity();
        activity.setActivityId("node-activity-1");
        activity.setOutputDatasetId("parsed_source.csv");
        activity.setSourceDatasetIds(List.of("source.csv"));
        activity.setMappingSpecId("a".repeat(64));
        activity.setGeneratedAt("2026-10-19T11:01:00Z");
        return activity;
    }

    private ProjectProvenanceRecord storedRecord() {
        ProjectProvenanceRecord record = new ProjectProvenanceRecord();
        record.setProjectId("project-1");
        record.setActivityId("node-activity-1");
        record.setSourceEventId("node-activity-1");
        record.setEventType(ProjectProvenanceRecord.EventType.DATASET_HARMONIZATION);
        record.setOutcome(ProjectProvenanceRecord.Outcome.SUCCEEDED);
        record.setOccurredAt(Instant.parse("2026-10-19T11:01:00Z"));
        record.setRecordedAt(record.getOccurredAt());
        record.setActorType(ProjectProvenanceRecord.ActorType.NODE);
        record.setActorId("node-1");
        record.setNodeId("node-1");
        record.setNodeName("Clinical node");
        record.setSourceDatasetIds(List.of("source.csv"));
        record.setGeneratedDatasetIds(List.of("parsed_source.csv"));
        record.setMappingSpecId("a".repeat(64));
        return record;
    }

    private NodeProvenanceEvent storedNodeEvent() {
        NodeProvenanceEvent event = new NodeProvenanceEvent();
        event.setSchemaVersion(1);
        event.setNodeId("node-1");
        event.setNodeName("Clinical node");
        event.setSourceEventId("node-activity-1");
        event.setOccurredAt(Instant.parse("2026-10-19T11:01:00Z"));
        event.setReceivedAt(event.getOccurredAt());
        event.setSourceDatasetIds(List.of("source.csv"));
        event.setOutputDatasetId("parsed_source.csv");
        event.setMappingSpecId("a".repeat(64));
        return event;
    }
}
