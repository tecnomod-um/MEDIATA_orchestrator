package org.taniwha.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.taniwha.model.Project;
import org.taniwha.repository.ProjectRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test to verify that default data including a default project is created on boot.
 */
class DataInitializationIT extends BaseIntegrationTest {

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void testDefaultProjectCreated() {
        // Given: Application has started

        // When: We query for projects
        List<Project> projects = projectRepository.findAll();

        // Then: At least one project should exist (the default project)
        assertThat(projects).isNotEmpty();

        // And: The default project should have the expected properties
        Project defaultProject = projectRepository.findByName("Default Project");
        assertThat(defaultProject).isNotNull();
        assertThat(defaultProject.getName()).isEqualTo("Default Project");
        assertThat(defaultProject.getDescription()).isEqualTo("Default project created on initialization");
        assertThat(defaultProject.getBadge()).isEqualTo("default");
    }
}
