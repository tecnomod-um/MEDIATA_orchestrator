package org.taniwha.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.taniwha.dto.SecurePoolParticipant;
import org.taniwha.dto.SecurePoolRosterRequest;
import org.taniwha.model.NodeInfo;
import org.taniwha.service.NodeService;
import org.taniwha.service.NodeAccessService;
import org.taniwha.util.JwtTokenUtil;
import java.util.*;

/** Identity directory only: it never receives data contributions or statistics. */
@RestController
@RequestMapping("/nodes/secure-pool")
public class SecurePoolRosterController {
    private final NodeService nodes;
    private final NodeAccessService access;
    private final JwtTokenUtil tokens;

    public SecurePoolRosterController(NodeService nodes,NodeAccessService access,JwtTokenUtil tokens) {
        this.nodes=nodes;this.access=access;this.tokens=tokens;
    }

    @PostMapping("/participants")
    public ResponseEntity<List<SecurePoolParticipant>> participants(@RequestBody List<String> ids,
                                      @RequestHeader("Authorization") String authorization) {
        String token=bearer(authorization);
        var roster=roster(ids);
        for(var participant:roster) if(!access.checkUserAccess(participant.nodeId(),token))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Node access denied");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roster);
    }

    @PostMapping("/roster")
    public ResponseEntity<List<SecurePoolParticipant>> nodeRoster(@RequestBody SecurePoolRosterRequest request,
                                      @RequestHeader(value="Authorization",required=false) String authorization) {
        if(request==null || request.nodeId()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        NodeInfo own=nodes.findNodeById(request.nodeId());
        if(own==null || !tokens.validateNodeSyncToken(bearer(authorization),request.nodeId(),"/nodes/secure-pool/roster",own.getPublicKey()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"A registered node signature is required");
        if(request.participantIds()==null || !request.participantIds().contains(request.nodeId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(roster(request.participantIds()));
    }

    private List<SecurePoolParticipant> roster(List<String> ids) {
        if(ids==null || ids.size()<2 || ids.size()>16 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size()!=ids.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Two to sixteen distinct nodes are required");
        var result=new ArrayList<SecurePoolParticipant>();var keys=new HashSet<String>();
        for(String id:ids.stream().sorted().toList()) {
            NodeInfo node=nodes.findNodeById(id);
            if(node==null || !Boolean.TRUE.equals(node.getActive()) || node.getPublicKey()==null || !keys.add(node.getPublicKey()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Node identities must be active and distinct");
            result.add(new SecurePoolParticipant(id,node.getPublicKey()));
        }
        return List.copyOf(result);
    }

    private static String bearer(String value) {
        if(value==null || !value.startsWith("Bearer ")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return value.substring(7);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> invalid(ResponseStatusException error) {
        var problem=ProblemDetail.forStatusAndDetail(error.getStatusCode(),
                error.getReason()==null?"Invalid secure pooling request":error.getReason());
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(problem);
    }
}
