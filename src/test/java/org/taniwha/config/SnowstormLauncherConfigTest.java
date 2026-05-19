package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SnowstormLauncherConfigTest {

    @Test
    void launchFullSnowstorm_returnsWhenDockerIsUnavailable() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.dockerAvailable = false;
        SnowstormLauncherConfig config = configuredLauncher(runner);

        config.launchFullSnowstorm().run();

        assertTrue(runner.commands.isEmpty());
        assertTrue(runner.httpChecks.isEmpty());
    }

    @Test
    void launchFullSnowstorm_reusesRunningContainersAndExistingImagesAndNetwork() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.containerStatuses.put("es", "running");
        runner.containerStatuses.put("snow", "running");
        SnowstormLauncherConfig config = configuredLauncher(runner);

        config.launchFullSnowstorm().run();

        assertTrue(runner.commands.contains("docker image inspect es-image"));
        assertTrue(runner.commands.contains("docker image inspect snow-image"));
        assertTrue(runner.commands.contains("docker network inspect snow-net"));
        assertFalse(runner.commands.stream().anyMatch(command -> command.startsWith("docker run")));
        assertEquals(List.of(
                "http://localhost:19200/",
                "http://localhost:19100/MAIN/concepts?term=diagn&activeFilter=true&limit=1"
        ), runner.httpChecks);
    }

    @Test
    void launchFullSnowstorm_pullsMissingImagesCreatesNetworkAndRunsContainers() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.commandResults.put("docker image inspect es-image", 1);
        runner.commandResults.put("docker image inspect snow-image", 1);
        runner.commandResults.put("docker network inspect snow-net", 1);
        runner.containerStatuses.put("es", "notfound");
        runner.containerStatuses.put("snow", "notfound");
        SnowstormLauncherConfig config = configuredLauncher(runner);

        config.launchFullSnowstorm().run();

        assertTrue(runner.commands.contains("docker pull es-image"));
        assertTrue(runner.commands.contains("docker pull snow-image"));
        assertTrue(runner.commands.contains("docker network create snow-net"));
        assertTrue(runner.commands.stream().anyMatch(command ->
                command.contains("docker run -d --name es")
                        && command.contains("-p 19200:9200")
                        && command.contains("-v es-volume:/usr/share/elasticsearch/data")
                        && command.endsWith("es-image")
        ));
        assertTrue(runner.commands.stream().anyMatch(command ->
                command.contains("docker run -d --name snow")
                        && command.contains("-p 19100:8080")
                        && command.contains("snow-image")
                        && command.contains("--elasticsearch.urls=http://es:9200")
                        && command.contains("SecurityAutoConfiguration")
        ));
    }

    @Test
    void launchFullSnowstorm_startsExitedContainers() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.containerStatuses.put("es", "exited");
        runner.containerStatuses.put("snow", "exited");
        SnowstormLauncherConfig config = configuredLauncher(runner);

        config.launchFullSnowstorm().run();

        assertTrue(runner.commands.contains("docker start es"));
        assertTrue(runner.commands.contains("docker start snow"));
        assertFalse(runner.commands.stream().anyMatch(command -> command.startsWith("docker run")));
    }

    @Test
    void launchFullSnowstorm_throwsWhenImagePullFails() {
        FakeRunner runner = new FakeRunner();
        runner.commandResults.put("docker image inspect es-image", 1);
        runner.commandResults.put("docker pull es-image", 1);
        SnowstormLauncherConfig config = configuredLauncher(runner);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.launchFullSnowstorm().run());

        assertEquals("Failed to pull image es-image", error.getMessage());
    }

    @Test
    void launchFullSnowstorm_throwsWhenNetworkCreationFails() {
        FakeRunner runner = new FakeRunner();
        runner.commandResults.put("docker network inspect snow-net", 1);
        runner.commandResults.put("docker network create snow-net", 1);
        SnowstormLauncherConfig config = configuredLauncher(runner);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.launchFullSnowstorm().run());

        assertEquals("Failed to create docker network snow-net", error.getMessage());
    }

    @Test
    void launchFullSnowstorm_throwsWhenStartingExitedContainerFails() {
        FakeRunner runner = new FakeRunner();
        runner.containerStatuses.put("es", "exited");
        runner.commandResults.put("docker start es", 1);
        SnowstormLauncherConfig config = configuredLauncher(runner);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.launchFullSnowstorm().run());

        assertEquals("Failed to start Elasticsearch container es", error.getMessage());
    }

    @Test
    void launchFullSnowstorm_throwsWhenContainerRunFails() {
        FakeRunner runner = new FakeRunner();
        runner.containerStatuses.put("es", "notfound");
        runner.commandResults.put(
                "docker run -d --name es --restart unless-stopped --network snow-net -p 19200:9200 "
                        + "-e discovery.type=single-node -e xpack.security.enabled=false -e node.name=snowstorm "
                        + "-e cluster.name=snowstorm-cluster -e ES_JAVA_OPTS=-Xms2g -Xmx2g "
                        + "-v es-volume:/usr/share/elasticsearch/data es-image",
                1
        );
        SnowstormLauncherConfig config = configuredLauncher(runner);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.launchFullSnowstorm().run());

        assertEquals("Failed to run Elasticsearch container es", error.getMessage());
    }

    @Test
    void launchFullSnowstorm_canRunSnowstormWithAuthEnabled() throws Exception {
        FakeRunner runner = new FakeRunner();
        runner.containerStatuses.put("es", "running");
        runner.containerStatuses.put("snow", "notfound");
        SnowstormLauncherConfig config = configuredLauncher(runner);
        ReflectionTestUtils.setField(config, "disableAuth", false);

        config.launchFullSnowstorm().run();

        String command = runner.commands.stream()
                .filter(value -> value.contains("docker run -d --name snow"))
                .findFirst()
                .orElseThrow();
        assertTrue(command.contains("--elasticsearch.urls=http://es:9200"));
        assertFalse(command.contains("SecurityAutoConfiguration"));
    }

    @Test
    void defaultRunnerReportsInvalidHttpAsNotResponding() {
        SnowstormLauncherConfig.DefaultLauncherCommandRunner runner =
                new SnowstormLauncherConfig.DefaultLauncherCommandRunner();

        assertFalse(runner.httpResponds("http://127.0.0.1:1/not-open"));
    }

    @Test
    void defaultRunnerReturnsCommandExitCodes() throws Exception {
        SnowstormLauncherConfig.DefaultLauncherCommandRunner runner =
                new SnowstormLauncherConfig.DefaultLauncherCommandRunner();

        assertEquals(0, runner.runAndLogIfFails(null, "sh", "-c", "printf ok"));
        assertEquals(7, runner.runAndLogIfFails(null, "sh", "-c", "printf bad && exit 7"));
    }

    private SnowstormLauncherConfig configuredLauncher(FakeRunner runner) {
        SnowstormLauncherConfig config = new SnowstormLauncherConfig(runner);
        ReflectionTestUtils.setField(config, "snowstormImage", "snow-image");
        ReflectionTestUtils.setField(config, "esImage", "es-image");
        ReflectionTestUtils.setField(config, "networkName", "snow-net");
        ReflectionTestUtils.setField(config, "snowstormContainer", "snow");
        ReflectionTestUtils.setField(config, "esContainer", "es");
        ReflectionTestUtils.setField(config, "snowstormHostPort", 19100);
        ReflectionTestUtils.setField(config, "snowstormContainerPort", 8080);
        ReflectionTestUtils.setField(config, "esHostPort", 19200);
        ReflectionTestUtils.setField(config, "startupTimeoutSeconds", 1);
        ReflectionTestUtils.setField(config, "disableAuth", true);
        ReflectionTestUtils.setField(config, "esVolume", "es-volume");
        return config;
    }

    private static class FakeRunner implements SnowstormLauncherConfig.LauncherCommandRunner {
        boolean dockerAvailable = true;
        final Map<String, String> containerStatuses = new HashMap<>();
        final Map<String, Integer> commandResults = new HashMap<>();
        final List<String> commands = new ArrayList<>();
        final List<String> httpChecks = new ArrayList<>();

        @Override
        public boolean dockerAvailable() {
            return dockerAvailable;
        }

        @Override
        public boolean httpResponds(String url) {
            httpChecks.add(url);
            return true;
        }

        @Override
        public String containerStatus(String name) {
            return containerStatuses.getOrDefault(name, "running");
        }

        @Override
        public int runAndLogIfFails(File dir, String... cmd) {
            String command = String.join(" ", cmd);
            commands.add(command);
            return commandResults.getOrDefault(command, 0);
        }

        @Override
        public void sleep(long millis) {
            // Tests never wait for real time.
        }
    }
}
