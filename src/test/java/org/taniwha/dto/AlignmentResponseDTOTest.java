package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AlignmentResponseDTOTest {

    @Test
    void testConstructorAndGetters() {
        AlignmentResponseDTO dto = new AlignmentResponseDTO(
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
        AlignmentResponseDTO dto = new AlignmentResponseDTO(false, "", false, "");

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
        AlignmentResponseDTO dto = new AlignmentResponseDTO(
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
        AlignmentResponseDTO dto = new AlignmentResponseDTO(
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
        AlignmentResponseDTO dto = new AlignmentResponseDTO(true, null, true, null);

        assertTrue(dto.isCsvSaved());
        assertTrue(dto.isRdfGenerated());
        assertNull(dto.getCsvMessage());
        assertNull(dto.getRdfMessage());
    }

    @Test
    void testUpdateMessages() {
        AlignmentResponseDTO dto = new AlignmentResponseDTO(true, "Initial", true, "Initial");

        dto.setCsvMessage("Updated CSV message");
        dto.setRdfMessage("Updated RDF message");

        assertEquals("Updated CSV message", dto.getCsvMessage());
        assertEquals("Updated RDF message", dto.getRdfMessage());
    }
}
