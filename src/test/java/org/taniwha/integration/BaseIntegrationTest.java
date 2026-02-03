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
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

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
    
    // Track allocated ports to avoid reuse within the same JVM
    private static final Set<Integer> allocatedPorts = new HashSet<>();
    
    // Port range for test services (high range to avoid conflicts with common services)
    private static final int MIN_PORT = 50000;
    private static final int MAX_PORT = 60000;
    private static final int MAX_RETRIES = 50;

    static {
        mongoDBContainer = new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
                .withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
                .withStartupTimeout(Duration.ofSeconds(60));
        mongoDBContainer.start();
        
        // Allocate a random available port for KDC server with retry logic
        kdcPort = findAvailablePort();
    }
    
    /**
     * Finds an available port in the range 50000-60000 with retry logic to minimize race conditions.
     * 
     * This method attempts to allocate a port by:
     * 1. Trying random ports in the high range (50000-60000) to avoid common service ports
     * 2. Verifying the port hasn't been allocated within this JVM session
     * 3. Retrying up to MAX_RETRIES times if binding fails
     * 
     * While a small race condition window still exists between releasing the socket
     * and the KDC server binding to it, using a high port range and retry logic
     * significantly reduces the probability of conflicts in test environments.
     * 
     * @return an available port number
     * @throws RuntimeException if no available port can be found after MAX_RETRIES attempts
     */
    private static int findAvailablePort() {
        Random random = new Random();
        
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            int port = MIN_PORT + random.nextInt(MAX_PORT - MIN_PORT);
            
            // Skip if we've already allocated this port in this JVM session
            if (allocatedPorts.contains(port)) {
                continue;
            }
            
            // Try to bind to the port
            try (ServerSocket socket = new ServerSocket(port)) {
                socket.setReuseAddress(true);
                // Successfully bound - mark as allocated and return
                allocatedPorts.add(port);
                return port;
            } catch (IOException e) {
                // Port is in use, try another
                continue;
            }
        }
        
        throw new RuntimeException(
            "Could not find available port for KDC server after " + MAX_RETRIES + " attempts"
        );
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
