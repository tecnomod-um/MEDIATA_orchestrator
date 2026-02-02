package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OntologyTermDTOTest {

    @Test
    void testNoArgsConstructor() {
        OntologyTermDTO dto = new OntologyTermDTO();
        assertNotNull(dto);
        assertNull(dto.getId());
        assertNull(dto.getLabel());
        assertNull(dto.getDescription());
        assertNull(dto.getIri());
    }

    @Test
    void testAllArgsConstructor() {
        OntologyTermDTO dto = new OntologyTermDTO("1", "Disease", "A disease condition", "http://example.org/disease");

        assertEquals("1", dto.getId());
        assertEquals("Disease", dto.getLabel());
        assertEquals("A disease condition", dto.getDescription());
        assertEquals("http://example.org/disease", dto.getIri());
    }

    @Test
    void testSetters() {
        OntologyTermDTO dto = new OntologyTermDTO();

        dto.setId("123");
        dto.setLabel("Symptom");
        dto.setDescription("A symptom description");
        dto.setIri("http://example.org/symptom");

        assertEquals("123", dto.getId());
        assertEquals("Symptom", dto.getLabel());
        assertEquals("A symptom description", dto.getDescription());
        assertEquals("http://example.org/symptom", dto.getIri());
    }

    @Test
    void testNullValues() {
        OntologyTermDTO dto = new OntologyTermDTO(null, null, null, null);

        assertNull(dto.getId());
        assertNull(dto.getLabel());
        assertNull(dto.getDescription());
        assertNull(dto.getIri());
    }

    @Test
    void testUpdateValues() {
        OntologyTermDTO dto = new OntologyTermDTO("1", "Old Label", "Old Desc", "http://old.com");

        dto.setLabel("New Label");
        dto.setDescription("New Desc");

        assertEquals("New Label", dto.getLabel());
        assertEquals("New Desc", dto.getDescription());
        assertEquals("1", dto.getId());  // ID unchanged
        assertEquals("http://old.com", dto.getIri());  // IRI unchanged
    }

    @Test
    void testMultipleTerms() {
        OntologyTermDTO term1 = new OntologyTermDTO("1", "Term1", "Desc1", "http://iri1.com");
        OntologyTermDTO term2 = new OntologyTermDTO("2", "Term2", "Desc2", "http://iri2.com");

        assertNotEquals(term1.getId(), term2.getId());
        assertNotEquals(term1.getLabel(), term2.getLabel());
        assertNotEquals(term1.getIri(), term2.getIri());
    }
}
