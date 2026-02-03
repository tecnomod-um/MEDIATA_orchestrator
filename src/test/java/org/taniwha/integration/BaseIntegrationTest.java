package org.taniwha.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Base class for integration tests that provides MongoDB test container setup.
 * All integration tests should extend this class to share the same MongoDB instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    // Singleton container shared across all test classes
    private static final GenericContainer<?> mongoDBContainer;

    static {
        mongoDBContainer = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
                .withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
        mongoDBContainer.start();
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        String connectionString = String.format("mongodb://%s:%d/test",
                mongoDBContainer.getHost(),
                mongoDBContainer.getMappedPort(27017));
        registry.add("spring.data.mongodb.uri", () -> connectionString);
        registry.add("jwt.secret", () -> "testSecretKeyThatIsExactly32!!!!");
        // Disable external service launchers in integration tests
        registry.add("snowstorm.enabled", () -> "false");
        registry.add("python.launcher.enabled", () -> "false");
        registry.add("kerberos.enabled", () -> "false");
        // Disable JWT authentication in tests
        registry.add("jwt.filter.enabled", () -> "false");
    }
}
