package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.service.RDFService;

import java.util.List;

@RestController
@RequestMapping("/rdf/suggestions")
public class RDFController {

    private static final Logger logger = LoggerFactory.getLogger(RDFController.class);
    private final RDFService rdfService;

    public RDFController(RDFService rdfService) {
        this.rdfService = rdfService;
    }

    @GetMapping("/class")
    public ResponseEntity<List<OntologyTermDTO>> getClassSuggestions(
            @RequestParam(name = "query", required = false) String query) {
        List<OntologyTermDTO> suggestions = rdfService.getClassSuggestions(query);
        return ResponseEntity.ok(suggestions);
    }

    @GetMapping("/property")
    public ResponseEntity<List<OntologyTermDTO>> getPropertySuggestions(
            @RequestParam(name = "query", required = false) String query) {
        List<OntologyTermDTO> suggestions = rdfService.getPropertySuggestions(query);
        return ResponseEntity.ok(suggestions);
    }
}
