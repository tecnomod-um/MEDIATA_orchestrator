package org.taniwha.integration;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

/**
 * Base class for integration tests that provides MongoDB test container setup.
 * All integration tests should extend this class to share the same MongoDB instance and application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public abstract class BaseIntegrationTest {

    // Singleton container shared across all test classes
    private static final GenericContainer<?> mongoDBContainer;
    
    // Singleton KDC port - allocated once and shared across all tests
    private static final int kdcPort;

    static {
        mongoDBContainer = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
                .withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
        mongoDBContainer.start();
        
        // Allocate a random available port for KDC server
        kdcPort = findAvailablePort();
    }
    
    /**
     * Finds an available port by binding to port 0 and then releasing it.
     * This method is not 100% race-condition free, but works well for tests.
     */
    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Could not find available port for KDC server", e);
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        String connectionString = String.format("mongodb://%s:%d/test",
                mongoDBContainer.getHost(),
                mongoDBContainer.getMappedPort(27017));
        registry.add("spring.data.mongodb.uri", () -> connectionString);
        registry.add("jwt.secret", () -> "testSecretKeyThatIsExactly32!!!!");
        // Use a dynamically allocated port for KDC server, shared across all test contexts
        registry.add("kerberos.kdc.port", () -> String.valueOf(kdcPort));
        // Disable external service launchers in integration tests
        registry.add("snowstorm.enabled", () -> "false");
        registry.add("python.launcher.enabled", () -> "false");
        // Disable JWT authentication in tests
        registry.add("jwt.filter.enabled", () -> "false");
    }
}
