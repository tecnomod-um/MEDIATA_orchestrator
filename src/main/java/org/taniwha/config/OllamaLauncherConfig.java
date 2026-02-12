package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Profile("!docker")
@Configuration
@ConditionalOnProperty(name = "ollama.launcher.enabled", havingValue = "true", matchIfMissing = true)
public class OllamaLauncherConfig {
    private static final Logger logger = LoggerFactory.getLogger(OllamaLauncherConfig.class);

    @Value("${ollama.image:${env.OLLAMA_IMAGE:ollama/ollama:latest}}")
    private String ollamaImage;

    @Value("${ollama.containerName:${env.OLLAMA_CONTAINER:ollama}}")
    private String ollamaContainer;

    @Value("${ollama.hostPort:${env.OLLAMA_HOST_PORT:11434}}")
    private int ollamaHostPort;

    @Value("${ollama.containerPort:${env.OLLAMA_CONTAINER_PORT:11434}}")
    private int ollamaContainerPort;

    @Value("${ollama.startupTimeoutSeconds:${env.OLLAMA_TIMEOUT:60}}")
    private int startupTimeoutSeconds;

    @Value("${ollama.model:${env.OLLAMA_MODEL:llama2}}")
    private String ollamaModel;

    @Value("${ollama.volume:${env.OLLAMA_VOLUME:ollama-models}}")
    private String ollamaVolume;

    @Bean
    CommandLineRunner launchOllama() {
        return args -> {
            if (!dockerAvailable()) {
                logger.warn("Docker not available. Ollama will not be auto-launched.");
                logger.warn("Please start Ollama manually: ollama serve");
                return;
            }

            logger.info("Starting Ollama launcher...");
            
            ensureImagePresent(ollamaImage);
            ensureOllamaRunning();
            waitForHttp("http://localhost:" + ollamaHostPort + "/", 
                    Duration.ofSeconds(startupTimeoutSeconds), "Ollama");
            
            // Pull model if needed
            pullModelIfNeeded();
        };
    }

    private void ensureOllamaRunning() throws Exception {
        String status = containerStatus(ollamaContainer);
        if ("running".equals(status)) {
            logger.info("Ollama container {} already running.", ollamaContainer);
            return;
        }
        if ("exited".equals(status)) {
            logger.info("Starting existing Ollama container {}...", ollamaContainer);
            if (runAndLogIfFails("docker", "start", ollamaContainer) != 0) {
                throw new IllegalStateException("Failed to start Ollama container " + ollamaContainer);
            }
            return;
        }

        logger.info("Creating and running Ollama container {}...", ollamaContainer);

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(ollamaContainer);
        cmd.add("--restart");
        cmd.add("unless-stopped");
        cmd.add("-p");
        cmd.add(ollamaHostPort + ":" + ollamaContainerPort);
        cmd.add("-v");
        cmd.add(ollamaVolume + ":/root/.ollama");
        cmd.add(ollamaImage);

        int rc = runAndLogIfFails(cmd.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException("Failed to run Ollama container " + ollamaContainer);
        }
    }

    private void pullModelIfNeeded() {
        try {
            logger.info("Checking if model {} is available...", ollamaModel);
            
            // Check if model exists
            Process checkProcess = new ProcessBuilder(
                "docker", "exec", ollamaContainer, "ollama", "list"
            ).redirectErrorStream(true).start();
            
            String output;
            try (Scanner s = new Scanner(checkProcess.getInputStream()).useDelimiter("\\A")) {
                output = s.hasNext() ? s.next() : "";
            }
            checkProcess.waitFor();
            
            if (output.contains(ollamaModel)) {
                logger.info("Model {} already available.", ollamaModel);
                return;
            }
            
            logger.info("Pulling model {}... This may take several minutes.", ollamaModel);
            Process pullProcess = new ProcessBuilder(
                "docker", "exec", ollamaContainer, "ollama", "pull", ollamaModel
            ).redirectErrorStream(true).start();
            
            // Stream output to logger
            try (Scanner s = new Scanner(pullProcess.getInputStream())) {
                while (s.hasNextLine()) {
                    String line = s.nextLine();
                    if (line.contains("success") || line.contains("digest:") || line.contains("total")) {
                        logger.info("  {}", line);
                    }
                }
            }
            
            pullProcess.waitFor();
            if (pullProcess.exitValue() == 0) {
                logger.info("Model {} pulled successfully.", ollamaModel);
            } else {
                logger.warn("Failed to pull model {}. Manual pull may be needed.", ollamaModel);
            }
        } catch (Exception e) {
            logger.warn("Could not auto-pull model {}: {}", ollamaModel, e.getMessage());
            logger.warn("You may need to manually run: docker exec {} ollama pull {}", 
                    ollamaContainer, ollamaModel);
        }
    }

    private void waitForHttp(String url, Duration timeout, String name) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        logger.info("Waiting for {} to be ready at {}...", name, url);
        
        while (System.currentTimeMillis() < deadline) {
            if (httpResponds(url)) {
                logger.info("{} is ready and reachable: {}", name, url);
                return;
            }
            Thread.sleep(1000);
        }
        logger.warn("{} not reachable after {} seconds: {}", name, timeout.getSeconds(), url);
        logger.warn("Try: docker logs --tail=50 {}", ollamaContainer);
    }

    private boolean httpResponds(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version")
                    .redirectErrorStream(true)
                    .start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureImagePresent(String image) throws Exception {
        if (runAndLogIfFails("docker", "image", "inspect", image) == 0) {
            logger.info("Docker image {} already present.", image);
            return;
        }
        logger.info("Pulling Docker image {}...", image);
        if (runAndLogIfFails("docker", "pull", image) != 0) {
            throw new IllegalStateException("Failed to pull image " + image);
        }
    }

    private String containerStatus(String name) throws Exception {
        Process p = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name)
                .redirectErrorStream(true)
                .start();
        String out;
        try (Scanner s = new Scanner(p.getInputStream()).useDelimiter("\\A")) {
            out = s.hasNext() ? s.next().trim() : "";
        }
        p.waitFor();
        if (p.exitValue() != 0) return "notfound";
        if ("true".equalsIgnoreCase(out)) return "running";
        return "exited";
    }

    private int runAndLogIfFails(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);

        Process p = pb.start();
        String out;
        try (Scanner s = new Scanner(p.getInputStream()).useDelimiter("\\A")) {
            out = s.hasNext() ? s.next() : "";
        }
        p.waitFor();

        int rc = p.exitValue();
        if (rc != 0) {
            logger.debug("Command failed (rc={}): {}\n{}", rc, String.join(" ", cmd), out);
        } else {
            logger.debug("Command ok: {}", String.join(" ", cmd));
        }
        return rc;
    }
}
