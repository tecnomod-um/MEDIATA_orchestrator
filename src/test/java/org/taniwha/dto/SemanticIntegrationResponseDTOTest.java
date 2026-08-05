package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticIntegrationResponseDTOTest {

    @Test
    void testConstructorAndGetters() {
        SemanticIntegrationResponseDTO dto = new SemanticIntegrationResponseDTO(
                true,
                "CSV saved successfully",
                true,
                "RDF generated successfully"
        );

        assertTrue(dto.isCsvSaved());
        assertEquals("CSV saved successfully", dto.getCsvMessage());
        assertTrue(dto.isRdfGenerated());
        assertEquals("RDF generated successfully", dto.getRdfMessage());
    }

    @Test
    void testSetters() {
        SemanticIntegrationResponseDTO dto = new SemanticIntegrationResponseDTO(false, "", false, "");

        dto.setCsvSaved(true);
        dto.setCsvMessage("CSV updated");
        dto.setRdfGenerated(true);
        dto.setRdfMessage("RDF updated");

        assertTrue(dto.isCsvSaved());
        assertEquals("CSV updated", dto.getCsvMessage());
        assertTrue(dto.isRdfGenerated());
        assertEquals("RDF updated", dto.getRdfMessage());
    }

    @Test
    void testFailureScenario() {
        SemanticIntegrationResponseDTO dto = new SemanticIntegrationResponseDTO(
                false,
                "Failed to save CSV",
                false,
                "Failed to generate RDF"
        );

        assertFalse(dto.isCsvSaved());
        assertFalse(dto.isRdfGenerated());
        assertTrue(dto.getCsvMessage().contains("Failed"));
        assertTrue(dto.getRdfMessage().contains("Failed"));
    }

    @Test
    void testPartialSuccess() {
        SemanticIntegrationResponseDTO dto = new SemanticIntegrationResponseDTO(
                true,
                "CSV saved",
                false,
                "RDF generation failed"
        );

        assertTrue(dto.isCsvSaved());
        assertFalse(dto.isRdfGenerated());
    }

    @Test
    void testNullMessages() {
        SemanticIntegrationResponseDTO dto = new SemanticIntegrationResponseDTO(true, null, true, null);

        assertTrue(dto.isCsvSaved());
        assertTrue(dto.isRdfGenerated());
        assertNull(dto.getCsvMessage());
        assertNull(dto.getRdfMessage());
    }

    @Test
    void testUpdateMessages() {
        SemanticIntegrationResponseDTO dto = new SemanticIntegrationResponseDTO(true, "Initial", true, "Initial");

        dto.setCsvMessage("Updated CSV message");
        dto.setRdfMessage("Updated RDF message");

        assertEquals("Updated CSV message", dto.getCsvMessage());
        assertEquals("Updated RDF message", dto.getRdfMessage());
    }
}
