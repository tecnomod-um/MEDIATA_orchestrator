package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Profile("!docker")
@Configuration
public class SnowstormLauncherConfig {
    private static final Logger logger = LoggerFactory.getLogger(SnowstormLauncherConfig.class);

    @Value("${snowstorm.image:${env.SNOWSTORM_IMAGE:snomedinternational/snowstorm:latest}}")
    private String snowstormImage;

    @Value("${snowstorm.esImage:${env.SNOWSTORM_ES_IMAGE:docker.elastic.co/elasticsearch/elasticsearch:8.11.1}}")
    private String esImage;

    @Value("${snowstorm.network:${env.SNOWSTORM_NETWORK:snowstorm-net}}")
    private String networkName;

    @Value("${snowstorm.containerName:${env.SNOWSTORM_CONTAINER:snowstorm}}")
    private String snowstormContainer;

    @Value("${snowstorm.esContainerName:${env.SNOWSTORM_ES_CONTAINER:elasticsearch}}")
    private String esContainer;

    @Value("${snowstorm.hostPort:${env.SNOWSTORM_HOST_PORT:9100}}")
    private int snowstormHostPort;

    @Value("${snowstorm.containerPort:${env.SNOWSTORM_CONTAINER_PORT:8080}}")
    private int snowstormContainerPort;

    @Value("${snowstorm.esHostPort:${env.SNOWSTORM_ES_HOST_PORT:9200}}")
    private int esHostPort;

    @Value("${snowstorm.startupTimeoutSeconds:${env.SNOWSTORM_TIMEOUT:120}}")
    private int startupTimeoutSeconds;

    @Value("${snowstorm.disableAuth:${env.SNOWSTORM_DISABLE_AUTH:true}}")
    private boolean disableAuth;

    @Value("${snowstorm.esVolume:${env.SNOWSTORM_ES_VOLUME:snowstorm-es-data}}")
    private String esVolume;

    @Bean
    CommandLineRunner launchFullSnowstorm() {
        return args -> {
            if (!dockerAvailable()) {
                logger.error("Docker not available.");
                return;
            }

            ensureImagePresent(esImage);
            ensureImagePresent(snowstormImage);

            ensureNetworkExists(networkName);

            ensureElasticsearchRunning();
            waitForHttp("http://localhost:" + esHostPort + "/", Duration.ofSeconds(startupTimeoutSeconds), "Elasticsearch");

            ensureSnowstormRunning();
            waitForHttp("http://localhost:" + snowstormHostPort + "/MAIN/concepts?term=diagn&activeFilter=true&limit=1",
                    Duration.ofSeconds(startupTimeoutSeconds), "Snowstorm");
        };
    }

    private void ensureElasticsearchRunning() throws Exception {
        String status = containerStatus(esContainer);
        if ("running".equals(status)) {
            logger.info("Elasticsearch container {} already running.", esContainer);
            return;
        }
        if ("exited".equals(status)) {
            logger.info("Starting existing Elasticsearch container {}...", esContainer);
            if (runAndLogIfFails(null, "docker", "start", esContainer) != 0) {
                throw new IllegalStateException("Failed to start Elasticsearch container " + esContainer);
            }
            return;
        }

        logger.info("Creating and running Elasticsearch container {}...", esContainer);

        List<String> cmd = new ArrayList<String>();
        cmd.add("docker"); cmd.add("run"); cmd.add("-d");
        cmd.add("--name"); cmd.add(esContainer);
        cmd.add("--restart"); cmd.add("unless-stopped");
        cmd.add("--network"); cmd.add(networkName);
        cmd.add("-p"); cmd.add(esHostPort + ":9200");
        cmd.add("-e"); cmd.add("discovery.type=single-node");
        cmd.add("-e"); cmd.add("xpack.security.enabled=false");
        cmd.add("-e"); cmd.add("node.name=snowstorm");
        cmd.add("-e"); cmd.add("cluster.name=snowstorm-cluster");
        cmd.add("-e"); cmd.add("ES_JAVA_OPTS=-Xms2g -Xmx2g");
        cmd.add("-v"); cmd.add(esVolume + ":/usr/share/elasticsearch/data");
        cmd.add(esImage);

        int rc = runAndLogIfFails(null, cmd.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException("Failed to run Elasticsearch container " + esContainer);
        }
    }

    private void ensureSnowstormRunning() throws Exception {
        String status = containerStatus(snowstormContainer);
        if ("running".equals(status)) {
            logger.info("Snowstorm container {} already running.", snowstormContainer);
            return;
        }
        if ("exited".equals(status)) {
            logger.info("Starting existing Snowstorm container {}...", snowstormContainer);
            if (runAndLogIfFails(null, "docker", "start", snowstormContainer) != 0) {
                throw new IllegalStateException("Failed to start Snowstorm container " + snowstormContainer);
            }
            return;
        }

        logger.info("Creating and running Snowstorm container {}...", snowstormContainer);

        List<String> cmd = new ArrayList<String>();
        cmd.add("docker"); cmd.add("run"); cmd.add("-d");
        cmd.add("--name"); cmd.add(snowstormContainer);
        cmd.add("--restart"); cmd.add("unless-stopped");
        cmd.add("--network"); cmd.add(networkName);
        cmd.add("-p"); cmd.add(snowstormHostPort + ":" + snowstormContainerPort);

        cmd.add(snowstormImage);

        // Point Snowstorm at Elasticsearch inside the docker network
        cmd.add("--elasticsearch.urls=http://" + esContainer + ":9200");
        if (disableAuth) {
            cmd.add("--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration");
        }

        int rc = runAndLogIfFails(null, cmd.toArray(new String[0]));
        if (rc != 0) {
            throw new IllegalStateException("Failed to run Snowstorm container " + snowstormContainer);
        }
    }

    private void ensureNetworkExists(String net) throws Exception {
        // docker network inspect <net> => 0 if exists
        if (runAndLogIfFails(null, "docker", "network", "inspect", net) == 0) return;

        logger.info("Creating docker network {}...", net);
        if (runAndLogIfFails(null, "docker", "network", "create", net) != 0) {
            throw new IllegalStateException("Failed to create docker network " + net);
        }
    }

    private void waitForHttp(String url, Duration timeout, String name) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (httpResponds(url)) {
                logger.info("{} reachable: {}", name, url);
                return;
            }
            Thread.sleep(750);
        }
        logger.warn("{} not reachable yet: {}", name, url);
        logger.warn("Try: docker logs --tail=200 {}", name.equals("Elasticsearch") ? esContainer : snowstormContainer);
    }

    private boolean httpResponds(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
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
        if (runAndLogIfFails(null, "docker", "image", "inspect", image) == 0) return;
        logger.info("Pulling Docker image {}", image);
        if (runAndLogIfFails(null, "docker", "pull", image) != 0) {
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

    private int runAndLogIfFails(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir);

        Process p = pb.start();
        String out;
        try (Scanner s = new Scanner(p.getInputStream()).useDelimiter("\\A")) {
            out = s.hasNext() ? s.next() : "";
        }
        p.waitFor();

        int rc = p.exitValue();
        if (rc != 0) {
            logger.error("Command failed (rc={}): {}\n{}", rc, join(cmd), out);
        } else {
            logger.debug("Command ok: {}", join(cmd));
        }
        return rc;
    }

    private String join(String[] cmd) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cmd.length; i++) {
            if (i > 0) b.append(' ');
            b.append(cmd[i]);
        }
        return b.toString();
    }
}
