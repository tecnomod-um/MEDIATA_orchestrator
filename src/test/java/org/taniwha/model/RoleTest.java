package org.taniwha.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleTest {

    @Test
    void testRoleGettersAndSetters() {
        Role role = new Role();
        
        role.setId("1");
        role.setName("ADMIN");
        
        assertEquals("1", role.getId());
        assertEquals("ADMIN", role.getName());
    }

    @Test
    void testRoleWithNullValues() {
        Role role = new Role();
        
        assertNull(role.getId());
        assertNull(role.getName());
    }

    @Test
    void testRoleCreation() {
        Role adminRole = new Role();
        adminRole.setId("1");
        adminRole.setName("ADMIN");
        
        Role userRole = new Role();
        userRole.setId("2");
        userRole.setName("USER");
        
        assertNotEquals(adminRole.getId(), userRole.getId());
        assertNotEquals(adminRole.getName(), userRole.getName());
    }

    @Test
    void testRoleIdUpdate() {
        Role role = new Role();
        role.setId("initial");
        assertEquals("initial", role.getId());
        
        role.setId("updated");
        assertEquals("updated", role.getId());
    }

    @Test
    void testRoleNameUpdate() {
        Role role = new Role();
        role.setName("USER");
        assertEquals("USER", role.getName());
        
        role.setName("ADMIN");
        assertEquals("ADMIN", role.getName());
    }
}
