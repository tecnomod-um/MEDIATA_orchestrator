package org.taniwha.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    void testUserConstructorAndGetters() {
        Role role = new Role();
        role.setId("1");
        role.setName("ADMIN");
        
        NodeInfo node = new NodeInfo();
        node.setNodeId("node1");
        
        List<Role> roles = new ArrayList<>();
        roles.add(role);
        
        List<NodeInfo> nodes = new ArrayList<>();
        nodes.add(node);
        
        User user = new User("123", "testuser", "password", "test@example.com", roles, nodes);
        
        assertEquals("123", user.getId());
        assertEquals("testuser", user.getUsername());
        assertEquals("password", user.getPassword());
        assertEquals("test@example.com", user.getEmail());
        assertEquals(1, user.getRoles().size());
        assertEquals(1, user.getNodeIds().size());
    }

    @Test
    void testUserSetters() {
        User user = new User("1", "user", "pass", "email", new ArrayList<>(), new ArrayList<>());
        
        user.setId("2");
        user.setUsername("newuser");
        user.setPassword("newpass");
        user.setEmail("newemail@test.com");
        
        assertEquals("2", user.getId());
        assertEquals("newuser", user.getUsername());
        assertEquals("newpass", user.getPassword());
        assertEquals("newemail@test.com", user.getEmail());
    }

    @Test
    void testUserWithNullCollections() {
        User user = new User("1", "user", "pass", "email", null, null);
        
        assertNull(user.getRoles());
        assertNull(user.getNodeIds());
    }

    @Test
    void testUserRoleManagement() {
        User user = new User("1", "user", "pass", "email", new ArrayList<>(), new ArrayList<>());
        
        Role adminRole = new Role();
        adminRole.setId("1");
        adminRole.setName("ADMIN");
        
        Role userRole = new Role();
        userRole.setId("2");
        userRole.setName("USER");
        
        List<Role> roles = new ArrayList<>();
        roles.add(adminRole);
        roles.add(userRole);
        user.setRoles(roles);
        
        assertEquals(2, user.getRoles().size());
        assertTrue(user.getRoles().contains(adminRole));
        assertTrue(user.getRoles().contains(userRole));
    }

    @Test
    void testUserNodeManagement() {
        User user = new User("1", "user", "pass", "email", new ArrayList<>(), new ArrayList<>());
        
        NodeInfo node1 = new NodeInfo();
        node1.setNodeId("node1");
        NodeInfo node2 = new NodeInfo();
        node2.setNodeId("node2");
        
        List<NodeInfo> nodes = new ArrayList<>();
        nodes.add(node1);
        nodes.add(node2);
        user.setNodeIds(nodes);
        
        assertEquals(2, user.getNodeIds().size());
    }
}
