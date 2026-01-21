package org.taniwha.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FieldMetadataDTOTest {

    @Test
    void testNoArgsConstructor() {
        FieldMetadataDTO dto = new FieldMetadataDTO();
        assertNotNull(dto);
        assertNull(dto.getName());
        assertNull(dto.getType());
        assertFalse(dto.isOptional());
    }

    @Test
    void testAllArgsConstructor() {
        FieldMetadataDTO dto = new FieldMetadataDTO("fieldName", true, "String");
        
        assertEquals("fieldName", dto.getName());
        assertTrue(dto.isOptional());
        assertEquals("String", dto.getType());
    }

    @Test
    void testSetters() {
        FieldMetadataDTO dto = new FieldMetadataDTO();
        
        dto.setName("testField");
        dto.setOptional(true);
        dto.setType("Integer");
        
        assertEquals("testField", dto.getName());
        assertTrue(dto.isOptional());
        assertEquals("Integer", dto.getType());
    }

    @Test
    void testOptionalField() {
        FieldMetadataDTO required = new FieldMetadataDTO("required", false, "String");
        FieldMetadataDTO optional = new FieldMetadataDTO("optional", true, "String");
        
        assertFalse(required.isOptional());
        assertTrue(optional.isOptional());
    }

    @Test
    void testDifferentTypes() {
        FieldMetadataDTO stringField = new FieldMetadataDTO("name", false, "String");
        FieldMetadataDTO intField = new FieldMetadataDTO("age", false, "Integer");
        FieldMetadataDTO boolField = new FieldMetadataDTO("active", true, "Boolean");
        
        assertEquals("String", stringField.getType());
        assertEquals("Integer", intField.getType());
        assertEquals("Boolean", boolField.getType());
    }
}
