package org.taniwha.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProjectProvenanceService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DATASET_REFERENCES = 1000;
    private static final int MAX_PROVENANCE_BATCH_SIZE = 100;
    private static final int MAX_DATASET_IDENTIFIER_LENGTH = 512;

    private final ProjectProvenanceRepository provenanceRepository;
    private final NodeProvenanceEventRepository nodeEventRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;

    public ProjectProvenanceService(ProjectProvenanceRepository provenanceRepository,
                                    NodeProvenanceEventRepository nodeEventRepository,
                                    ProjectRepository projectRepository,
                                    UserRepository userRepository) {
        this.provenanceRepository = provenanceRepository;
        this.nodeEventRepository = nodeEventRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
    }

    public void recordProjectNodeMembership(String projectId,
                                            String nodeId,
                                            String nodeName,
                                            boolean added,
                                            ProjectProvenanceRecord.ActorType actorType,
                                            String actorId) {
        if (isBlank(projectId) || isBlank(nodeId)) {
            return;
        }

        ProjectProvenanceRecord record = baseRecord(
                projectId,
                added ? ProjectProvenanceRecord.EventType.PROJECT_NODE_ADDED
                        : ProjectProvenanceRecord.EventType.PROJECT_NODE_REMOVED,
                ProjectProvenanceRecord.Outcome.SUCCEEDED,
                actorType == null ? ProjectProvenanceRecord.ActorType.SYSTEM : actorType,
                actorId
        );
        record.setNodeId(nodeId.trim());
        record.setNodeName(trimToNull(nodeName));
        provenanceRepository.save(record);
    }

    public void recordProjectNodeMembershipAsCurrentActor(String projectId,
                                                          String nodeId,
                                                          String nodeName,
                                                          boolean added) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && !isBlank(authentication.getName())) {
            recordProjectNodeMembership(projectId, nodeId, nodeName, added,
                    ProjectProvenanceRecord.ActorType.USER, authentication.getName());
        } else {
            recordProjectNodeMembership(projectId, nodeId, nodeName, added,
                    ProjectProvenanceRecord.ActorType.SYSTEM, "MEDIATA orchestrator");
        }
    }

    public void recordUserNodeAccess(String projectId,
                                     String nodeId,
                                     String nodeName,
                                     Authentication authentication) {
        requireAuthenticated(authentication);
        String normalizedNodeId = requireNodeId(nodeId);
        List<Project> projects;
        if (isBlank(projectId)) {
            projects = projectRepository.findByNodeIdsContaining(normalizedNodeId);
        } else {
            requireProjectNodeAccess(authentication, projectId, normalizedNodeId);
            projects = List.of(requireProject(projectId));
        }
        for (Project project : projects) {
            requireProjectNodeAccess(authentication, project.getId(), normalizedNodeId);
            ProjectProvenanceRecord record = baseRecord(
                    project.getId(),
                    ProjectProvenanceRecord.EventType.USER_NODE_ACCESS,
                    ProjectProvenanceRecord.Outcome.SUCCEEDED,
                    ProjectProvenanceRecord.ActorType.USER,
                    authentication.getName()
            );
            record.setNodeId(normalizedNodeId);
            record.setNodeName(trimToNull(nodeName));
            provenanceRepository.save(record);
        }
    }

    public List<String> ingestNodeProvenance(String nodeId,
                                             String nodeName,
                                             List<NodeProvenanceActivity> activities) {
        String normalizedNodeId = requireNodeId(nodeId);
        if (activities == null || activities.isEmpty()) {
            return List.of();
        }

        List<Project> projects = projectRepository.findByNodeIdsContaining(normalizedNodeId);
        Set<String> acknowledged = new LinkedHashSet<>();
        for (NodeProvenanceActivity activity : activities.stream()
                .filter(Objects::nonNull)
                .limit(MAX_PROVENANCE_BATCH_SIZE)
                .toList()) {
            String sourceEventId = trimToNull(activity.getActivityId());
            String outputDatasetId = trimToNull(activity.getOutputDatasetId());
            String mappingSpecId = trimToNull(activity.getMappingSpecId());
            if (!isSafeEventId(sourceEventId) || !isSafeDatasetId(outputDatasetId)
                    || !isSha256(mappingSpecId)) {
                continue;
            }
            Instant occurredAt = parseInstant(activity.getGeneratedAt());
            if (occurredAt == null) {
                continue;
            }
            List<String> sourceDatasetIds = normalizeDatasetIds(activity.getSourceDatasetIds());
            NodeProvenanceEvent nodeEvent = findOrStoreNodeEvent(
                    normalizedNodeId, nodeName, sourceEventId, outputDatasetId,
                    mappingSpecId, occurredAt, sourceDatasetIds);
            if (nodeEvent == null || !matches(nodeEvent, outputDatasetId, mappingSpecId,
                    occurredAt, sourceDatasetIds)) {
                continue;
            }

            for (Project project : projects) {
                if (project == null || isBlank(project.getId())) {
                    throw new IllegalStateException("Project membership contains an invalid project");
                }
                if (provenanceRepository.existsByProjectIdAndNodeIdAndSourceEventId(
                        project.getId(), normalizedNodeId, sourceEventId)) {
                    continue;
                }
                ProjectProvenanceRecord record = baseRecord(
                        project.getId(),
                        ProjectProvenanceRecord.EventType.DATASET_HARMONIZATION,
                        ProjectProvenanceRecord.Outcome.SUCCEEDED,
                        ProjectProvenanceRecord.ActorType.NODE,
                        normalizedNodeId
                );
                record.setActivityId(sourceEventId);
                record.setSourceEventId(sourceEventId);
                record.setOccurredAt(nodeEvent.getOccurredAt());
                record.setNodeId(normalizedNodeId);
                record.setNodeName(nodeEvent.getNodeName());
                record.setSourceDatasetIds(nodeEvent.getSourceDatasetIds());
                record.setGeneratedDatasetIds(List.of(nodeEvent.getOutputDatasetId()));
                record.setMappingSpecId(nodeEvent.getMappingSpecId());
                record.setMessage("Associated with this project from orchestrator-owned node membership at ingestion.");
                try {
                    provenanceRepository.save(record);
                } catch (DuplicateKeyException ignored) {
                    // A concurrent delivery inserted the same immutable node event.
                }
            }
            acknowledged.add(sourceEventId);
        }
        return List.copyOf(acknowledged);
    }

    private NodeProvenanceEvent findOrStoreNodeEvent(String nodeId,
                                                     String nodeName,
                                                     String sourceEventId,
                                                     String outputDatasetId,
                                                     String mappingSpecId,
                                                     Instant occurredAt,
                                                     List<String> sourceDatasetIds) {
        Optional<NodeProvenanceEvent> existing =
                nodeEventRepository.findByNodeIdAndSourceEventId(nodeId, sourceEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        NodeProvenanceEvent event = new NodeProvenanceEvent();
        event.setSchemaVersion(1);
        event.setNodeId(nodeId);
        event.setNodeName(trimToNull(nodeName));
        event.setSourceEventId(sourceEventId);
        event.setOccurredAt(occurredAt);
        event.setReceivedAt(Instant.now());
        event.setSourceDatasetIds(sourceDatasetIds);
        event.setOutputDatasetId(outputDatasetId);
        event.setMappingSpecId(mappingSpecId);
        try {
            return nodeEventRepository.insert(event);
        } catch (DuplicateKeyException ignored) {
            return nodeEventRepository.findByNodeIdAndSourceEventId(nodeId, sourceEventId)
                    .orElse(null);
        }
    }

    private boolean matches(NodeProvenanceEvent event,
                            String outputDatasetId,
                            String mappingSpecId,
                            Instant occurredAt,
                            List<String> sourceDatasetIds) {
        return Objects.equals(event.getOutputDatasetId(), outputDatasetId)
                && Objects.equals(event.getMappingSpecId(), mappingSpecId)
                && Objects.equals(event.getOccurredAt(), occurredAt)
                && Objects.equals(safeList(event.getSourceDatasetIds()), sourceDatasetIds);
    }

    public Page<ProjectProvenanceRecord> listActivity(String projectId,
                                                      int page,
                                                      int size,
                                                      Authentication authentication) {
        requireAdmin(authentication);
        requireProject(projectId);
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));
        return provenanceRepository.findByProjectIdOrderByOccurredAtDesc(
                projectId.trim(), PageRequest.of(safePage, safeSize));
    }

    public ProjectProvenanceRecord getActivity(String projectId,
                                               String eventId,
                                               Authentication authentication) {
        requireAdmin(authentication);
        requireProject(projectId);
        return provenanceRepository.findByIdAndProjectId(eventId, projectId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Activity record not found"));
    }

    public Map<String, Object> exportProvO(String projectId, Authentication authentication) {
        requireProjectAccess(authentication, projectId);
        Project project = requireProject(projectId);
        List<ProjectProvenanceRecord> records =
                provenanceRepository.findByProjectIdOrderByOccurredAtAsc(project.getId());
        return buildProvO(project, records);
    }

    private void requireProjectNodeAccess(Authentication authentication, String projectId, String nodeId) {
        requireAuthenticated(authentication);
        Project project = requireProject(projectId);
        String normalizedNodeId = trimToNull(nodeId);
        if (normalizedNodeId == null || project.getNodeIds() == null
                || project.getNodeIds().stream().filter(Objects::nonNull)
                .map(String::trim).noneMatch(normalizedNodeId::equals)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node is not part of this project");
        }
        if (isAdmin(authentication)) {
            return;
        }
        User user = userRepository.findByUsername(authentication.getName());
        boolean allowed = user != null && user.getNodeIds() != null && user.getNodeIds().stream()
                .filter(Objects::nonNull)
                .map(NodeInfo::getNodeId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(normalizedNodeId::equals);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project node access denied");
        }
    }

    private void requireProjectAccess(Authentication authentication, String projectId) {
        requireAuthenticated(authentication);
        Project project = requireProject(projectId);
        if (isAdmin(authentication)) {
            return;
        }
        User user = userRepository.findByUsername(authentication.getName());
        Set<String> projectNodeIds = project.getNodeIds() == null ? Set.of() : project.getNodeIds().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .collect(Collectors.toSet());
        boolean allowed = user != null && user.getNodeIds() != null && user.getNodeIds().stream()
                .filter(Objects::nonNull)
                .map(NodeInfo::getNodeId)
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(projectNodeIds::contains);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project access denied");
        }
    }

    private void requireAdmin(Authentication authentication) {
        requireAuthenticated(authentication);
        if (!isAdmin(authentication)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required");
        }
    }

    private void requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || isBlank(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required");
        }
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    private Project requireProject(String projectId) {
        if (isBlank(projectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project identifier is required");
        }
        return projectRepository.findById(projectId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    private ProjectProvenanceRecord baseRecord(String projectId,
                                               ProjectProvenanceRecord.EventType eventType,
                                               ProjectProvenanceRecord.Outcome outcome,
                                               ProjectProvenanceRecord.ActorType actorType,
                                               String actorId) {
        Instant now = Instant.now();
        ProjectProvenanceRecord record = new ProjectProvenanceRecord();
        record.setProjectId(projectId.trim());
        record.setActivityId(UUID.randomUUID().toString());
        record.setEventType(eventType);
        record.setOutcome(outcome);
        record.setOccurredAt(now);
        record.setRecordedAt(now);
        record.setActorType(actorType);
        record.setActorId(trimToNull(actorId));
        return record;
    }

    private List<String> normalizeDatasetIds(List<String> datasetIds) {
        if (datasetIds == null) {
            return List.of();
        }
        return datasetIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .filter(this::isSafeDatasetId)
                .distinct()
                .limit(MAX_DATASET_REFERENCES)
                .toList();
    }

    private String requireNodeId(String nodeId) {
        String normalized = trimToNull(nodeId);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Node identifier is required");
        }
        return normalized;
    }

    private boolean isSha256(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }

    private boolean isSafeEventId(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9._:-]+");
    }

    private boolean isSafeDatasetId(String value) {
        return value != null && value.length() <= MAX_DATASET_IDENTIFIER_LENGTH
                && value.chars().allMatch(character -> character >= 0x20 && character != 0x7f);
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> buildProvO(Project project, List<ProjectProvenanceRecord> records) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("prov", "http://www.w3.org/ns/prov#");
        context.put("dcterms", "http://purl.org/dc/terms/");
        context.put("mediata", "https://semantics.inf.um.es/mediata/ns#");
        context.put("xsd", "http://www.w3.org/2001/XMLSchema#");

        String projectUri = projectUri(project.getId());
        Map<String, Map<String, Object>> graph = new LinkedHashMap<>();
        graph.put(projectUri, graphNode(projectUri, "prov:Entity", project.getName()));

        Map<String, List<ProjectProvenanceRecord>> activities = records.stream()
                .filter(record -> !isBlank(record.getActivityId()))
                .collect(Collectors.groupingBy(
                        ProjectProvenanceRecord::getActivityId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        for (Map.Entry<String, List<ProjectProvenanceRecord>> entry : activities.entrySet()) {
            List<ProjectProvenanceRecord> activityRecords = entry.getValue().stream()
                    .sorted(Comparator.comparing(
                            ProjectProvenanceRecord::getOccurredAt,
                            Comparator.nullsLast(Comparator.naturalOrder())
                    ))
                    .toList();
            ProjectProvenanceRecord first = activityRecords.get(0);
            ProjectProvenanceRecord last = activityRecords.get(activityRecords.size() - 1);
            String activityUri = "urn:uuid:" + entry.getKey();

            Map<String, Object> activity = graphNode(activityUri, "prov:Activity", humanEventName(first.getEventType()));
            activity.put("mediata:eventType", first.getEventType().name());
            activity.put("mediata:project", idRef(projectUri));
            activity.put("mediata:outcome", last.getOutcome().name());
            if (first.getOccurredAt() != null) {
                activity.put("prov:startedAtTime", typedDate(first.getOccurredAt()));
            }
            if (last.getOutcome() != ProjectProvenanceRecord.Outcome.STARTED
                    && last.getOccurredAt() != null) {
                activity.put("prov:endedAtTime", typedDate(last.getOccurredAt()));
            }

            if (!isBlank(first.getNodeId())) {
                String nodeUri = nodeUri(first.getNodeId());
                graph.putIfAbsent(nodeUri, graphNode(nodeUri, "prov:SoftwareAgent", first.getNodeName()));
            }
            ProjectProvenanceRecord.ActorType actorType = first.getActorType() == null
                    ? ProjectProvenanceRecord.ActorType.SYSTEM
                    : first.getActorType();
            String agentUri = switch (actorType) {
                case NODE -> nodeUri(first.getNodeId());
                case USER -> "urn:mediata:agent:authenticated-user";
                case SYSTEM -> "urn:mediata:agent:orchestrator";
            };
            if (actorType == ProjectProvenanceRecord.ActorType.USER) {
                graph.putIfAbsent(agentUri,
                        graphNode(agentUri, "prov:Person", "Authenticated MEDIATA user"));
            } else if (actorType == ProjectProvenanceRecord.ActorType.SYSTEM) {
                graph.putIfAbsent(agentUri,
                        graphNode(agentUri, "prov:SoftwareAgent", "MEDIATA orchestrator"));
            }
            activity.put("prov:wasAssociatedWith", idRef(agentUri));

            Set<String> used = new LinkedHashSet<>();
            Set<String> generated = new LinkedHashSet<>();
            String planUriForAssociation = null;
            for (ProjectProvenanceRecord record : activityRecords) {
                List<String> recordSourceUris = new ArrayList<>();
                for (String source : safeList(record.getSourceDatasetIds())) {
                    String entityUri = datasetUri(record.getNodeId(), source);
                    graph.putIfAbsent(entityUri, graphNode(entityUri, "prov:Entity", source));
                    used.add(entityUri);
                    recordSourceUris.add(entityUri);
                }
                for (String output : safeList(record.getGeneratedDatasetIds())) {
                    String entityUri = datasetUri(record.getNodeId(), output);
                    Map<String, Object> outputEntity = graphNode(entityUri, "prov:Entity", output);
                    outputEntity.put("prov:wasGeneratedBy", idRef(activityUri));
                    if (!recordSourceUris.isEmpty()) {
                        outputEntity.put("prov:wasDerivedFrom",
                                recordSourceUris.stream().map(this::idRef).toList());
                    }
                    if (!isBlank(record.getNodeId())) {
                        outputEntity.put("prov:wasAttributedTo", idRef(nodeUri(record.getNodeId())));
                    }
                    if (record.getOccurredAt() != null) {
                        outputEntity.put("prov:generatedAtTime", typedDate(record.getOccurredAt()));
                    }
                    graph.put(entityUri, outputEntity);
                    generated.add(entityUri);
                }
                if (!isBlank(record.getMappingSpecId())) {
                    String planUri = "urn:sha256:" + record.getMappingSpecId();
                    Map<String, Object> plan = graphNode(planUri, "prov:Plan", "MEDIATA mapping specification");
                    plan.put("dcterms:identifier", record.getMappingSpecId());
                    graph.putIfAbsent(planUri, plan);
                    used.add(planUri);
                    planUriForAssociation = planUri;
                }
                if (!isBlank(record.getNodeJobId())) {
                    activity.put("mediata:nodeJobId", record.getNodeJobId());
                }
            }

            if (first.getEventType() == ProjectProvenanceRecord.EventType.PROJECT_NODE_ADDED) {
                String membershipUri = membershipUri(project.getId(), first.getNodeId());
                graph.putIfAbsent(membershipUri, graphNode(membershipUri, "prov:Entity", "Project node membership"));
                generated.add(membershipUri);
            } else if (first.getEventType() == ProjectProvenanceRecord.EventType.PROJECT_NODE_REMOVED) {
                activity.put("prov:invalidated", idRef(membershipUri(project.getId(), first.getNodeId())));
            } else if (first.getEventType() == ProjectProvenanceRecord.EventType.USER_NODE_ACCESS) {
                used.add(projectUri);
            }

            if (!used.isEmpty()) {
                activity.put("prov:used", used.stream().map(this::idRef).toList());
            }
            if (!generated.isEmpty()) {
                activity.put("prov:generated", generated.stream().map(this::idRef).toList());
            }
            if (planUriForAssociation != null && !isBlank(first.getNodeId())) {
                Map<String, Object> association = new LinkedHashMap<>();
                association.put("@type", "prov:Association");
                association.put("prov:agent", idRef(nodeUri(first.getNodeId())));
                association.put("prov:hadPlan", idRef(planUriForAssociation));
                activity.put("prov:qualifiedAssociation", association);
            }
            graph.put(activityUri, activity);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("@context", context);
        result.put("@id", projectUri + "/provenance");
        result.put("@type", "prov:Bundle");
        result.put("@graph", new ArrayList<>(graph.values()));
        return result;
    }

    private Map<String, Object> graphNode(String id, String type, String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@id", id);
        node.put("@type", type);
        if (!isBlank(label)) {
            node.put("dcterms:title", label);
        }
        return node;
    }

    private Map<String, String> idRef(String id) {
        return Map.of("@id", id);
    }

    private Map<String, String> typedDate(Instant instant) {
        return Map.of("@value", instant.toString(), "@type", "xsd:dateTime");
    }

    private String projectUri(String projectId) {
        return "urn:mediata:project:" + encode(projectId);
    }

    private String nodeUri(String nodeId) {
        return "urn:mediata:node:" + encode(nodeId);
    }

    private String datasetUri(String nodeId, String datasetId) {
        return "urn:mediata:dataset:" + encode(nodeId) + ":" + encode(datasetId);
    }

    private String membershipUri(String projectId, String nodeId) {
        return projectUri(projectId) + ":node-membership:" + encode(nodeId);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "unknown" : value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private String humanEventName(ProjectProvenanceRecord.EventType eventType) {
        return switch (eventType) {
            case PROJECT_NODE_ADDED -> "Node added to MEDIATA project";
            case PROJECT_NODE_REMOVED -> "Node removed from MEDIATA project";
            case USER_NODE_ACCESS -> "MEDIATA project node accessed";
            case DATASET_HARMONIZATION -> "MEDIATA dataset harmonization";
        };
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
