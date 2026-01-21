package org.taniwha.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that provides MongoDB test container setup.
 * All integration tests should extend this class to share the same MongoDB instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        // Use connection string format without replica set to avoid hostname resolution issues
        registry.add("spring.data.mongodb.uri", () -> 
            String.format("mongodb://%s:%d/test", 
                mongoDBContainer.getHost(), 
                mongoDBContainer.getFirstMappedPort()));
        registry.add("jwt.secret", () -> "testSecretKeyThatIs32CharsLong!");
        // Disable external service launchers in integration tests
        registry.add("snowstorm.enabled", () -> "false");
        registry.add("python.launcher.enabled", () -> "false");
        // Disable JWT authentication in tests
        registry.add("jwt.filter.enabled", () -> "false");
    }
}
