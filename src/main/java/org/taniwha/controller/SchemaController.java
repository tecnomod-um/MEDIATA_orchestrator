package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.service.SchemaService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/nodes/schema")
public class SchemaController {

    private static final Logger logger = LoggerFactory.getLogger(SchemaController.class);
    private final SchemaService schemaService;

    public SchemaController(SchemaService schemaService) {
        this.schemaService = schemaService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveSchema(@RequestBody Map<String, Object> request) {
        Map<String, Object> response = new HashMap<>();
        if (!request.containsKey("schema")) {
            response.put("error", "Missing 'schema' field in request body");
            return ResponseEntity.badRequest().body(response);
        }
        try {
            Object schemaObject = request.get("schema");
            // Save the properly serialized schema
            schemaService.saveSchema(schemaObject);
            response.put("message", "Schema saved successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Failed to save schema", e);
            response.put("error", "Failed to save schema: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getSchema() {
        Map<String, Object> response = new HashMap<>();
        String schema = schemaService.getSchema();
        if (schema == null) {
            response.put("error", "No schema found");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        }
        response.put("schema", schema);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> removeSchema() {
        Map<String, Object> response = new HashMap<>();
        schemaService.removeSchema();
        response.put("message", "Schema removed successfully");
        return ResponseEntity.ok(response);
    }
}
