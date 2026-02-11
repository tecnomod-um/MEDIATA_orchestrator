package org.taniwha.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.MappingSuggestResponseDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.service.MappingService;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mappings")
public class MappingController {

    private final MappingService mappingService;

    public MappingController(MappingService mappingService) {
        this.mappingService = mappingService;
    }

    @PostMapping("/suggest")
    public ResponseEntity<MappingSuggestResponseDTO> suggest(@RequestBody MappingSuggestRequestDTO req) {
        List<Map<String, SuggestedMappingDTO>> hierarchy = mappingService.suggestMappings(req);
        if (hierarchy == null) hierarchy = Collections.emptyList();
        String msg = hierarchy.isEmpty() ? "No suggestions produced." : "Suggestions computed.";
        return ResponseEntity.ok(new MappingSuggestResponseDTO(true, msg, hierarchy));
    }
}
