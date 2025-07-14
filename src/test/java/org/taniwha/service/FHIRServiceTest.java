package org.taniwha.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FHIRServiceTest {

    private FHIRService service;

    @BeforeEach
    void setUp() {
        service = new FHIRService();
    }

    @Test
    void processClusters_returnsNonEmpty() throws IOException {
        String result = service.processClusters("{ \"foo\": \"bar\" }");
        assertNotNull(result, "Expected a non-null response");
        assertFalse(result.isEmpty(), "Expected the returned JSON to be non-empty");
    }
}
