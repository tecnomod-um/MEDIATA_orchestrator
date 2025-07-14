package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class SchemaService {
    private final ObjectMapper objectMapper;
    private String schema;

    public SchemaService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public synchronized void saveSchema(Object schemaObject) throws Exception {
        // Convert the object into a properly formatted JSON string
        this.schema = objectMapper.writeValueAsString(schemaObject);
    }

    public synchronized String getSchema() {
        return this.schema;
    }

    public synchronized void removeSchema() {
        this.schema = null;
    }
}
