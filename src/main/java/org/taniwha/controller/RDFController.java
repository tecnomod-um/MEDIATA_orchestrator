package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.AlignmentResponseDTO;
import org.taniwha.dto.FieldMetadataDTO;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.service.RDFService;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/rdf")
public class RDFController {

    private static final Logger logger = LoggerFactory.getLogger(RDFController.class);
    private final RDFService rdfService;

    public RDFController(RDFService rdfService) {
        this.rdfService = rdfService;
    }

    @GetMapping("/class")
    public ResponseEntity<List<OntologyTermDTO>> getClassSuggestions(
            @RequestParam(name = "query", required = false) String query) {
        logger.debug("Fetching classes");
        List<OntologyTermDTO> suggestions = rdfService.getClassSuggestions(query);
        return ResponseEntity.ok(suggestions);
    }

    @GetMapping("/class/{type}")
    public ResponseEntity<List<FieldMetadataDTO>> getClassFields(@PathVariable("type") String type) {
        logger.debug("Fetching fields for {}", type);
        List<FieldMetadataDTO> fields = rdfService.getClassFields(type);
        return ResponseEntity.ok(fields);
    }

    @GetMapping("/snomed")
    public ResponseEntity<List<OntologyTermDTO>> getSNOMEDTermSuggestions(@RequestParam(name = "query", required = false) String query) {
        logger.debug("Fetching SNOMED term suggestions for query: {}", query);
        List<OntologyTermDTO> suggestions = rdfService.getSNOMEDTermSuggestions(query);
        return ResponseEntity.ok(suggestions);
    }

    @PostMapping("/semanticalignment")
    public ResponseEntity<AlignmentResponseDTO> uploadMappings(@RequestBody String csvText) {
        logger.debug("Received mapping CSV ({} bytes)", csvText.length());
        boolean csvSaved;
        String csvMsg;
        try {
            rdfService.writeCsv(csvText);
            csvSaved = true;
            csvMsg = "CSV saved successfully";
        } catch (IOException e) {
            csvSaved = false;
            csvMsg = "Failed to save CSV: " + e.getMessage();
        }

        boolean rdfGen = false;
        String rdfMsg;
        if (csvSaved) {
            try {
                String body = rdfService.generateRdf();
                rdfGen = true;
                rdfMsg = body;
            } catch (Exception e) {
                rdfMsg = "RDF generation failed: " + e.getMessage();
            }
        } else
            rdfMsg = "Skipped RDF generation because CSV write failed";
        AlignmentResponseDTO result = new AlignmentResponseDTO(csvSaved, csvMsg, rdfGen, rdfMsg);
        return ResponseEntity.ok(result);
    }
}
