package org.taniwha.service.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.config.MappingConfig;
import org.taniwha.config.RestTemplateConfig;
import org.taniwha.model.ColumnRecord;
import org.taniwha.service.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live integration test for the OpenMed → Snowstorm terminology pipeline.
 *
 * <h3>What it tests</h3>
 * <ol>
 *   <li>Starts Elasticsearch + Snowstorm as Docker containers (reuses if already running).</li>
 *   <li>Seeds Snowstorm with medical concepts via {@code POST /browser/MAIN/concepts}.</li>
 *   <li>Starts a minimal inline Java {@link HttpServer} on port 8000 as the rdf-builder bridge:
 *       {@code GET /term/<query>} → Snowstorm concepts API → {@code ["conceptId|label", …]}.</li>
 *   <li>Starts the OpenMed Python NER service from source.</li>
 *   <li>Runs known medical columns through
 *       {@link OpenMedTerminologyService#inferBatch} →
 *       {@link TerminologyLookupService#batchLookupTerminology}.</li>
 *   <li>Asserts that known medical value terms (hypertension, stroke, diabetes mellitus,
 *       aspirin, warfarin) resolve to <em>non-empty</em> valid SNOMED codes.</li>
 *   <li>Asserts every resolved terminology is {@code ^\d{6,}(\|.+)?$} or empty.</li>
 *   <li>Writes Markdown + JSON report to {@code target/openmed-report/}.</li>
 * </ol>
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application-test.properties", properties = {
        "rdfbuilder.csvpath=/tmp/test.csv",
        "rdfbuilder.service.url=http://localhost:8000",
        "openmed.service.url=http://localhost:8002",
        "openmed.enabled=true",
        "openmed.timeout.ms=30000",
        "openmed.launcher.enabled=false",
        "snowstorm.enabled=true",
        "snowstorm.launcher.enabled=false",
        "terminology.fallback.enabled=false",
        "llm.enabled=false",
        "spring.datasource.enabled=false",
        "spring.data.mongodb.auto-index-creation=false",
        "spring.main.allow-bean-definition-overriding=true"
})
@DisplayName("OpenMed + Snowstorm – live terminology pipeline report test (actual model + real SNOMED lookup)")
public class OpenMedTerminologyReportTest {

    // -----------------------------------------------------------------------
    // Spring context
    // -----------------------------------------------------------------------

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            MongoAutoConfiguration.class
    })
    static class TestConfig {

        @Bean(destroyMethod = "shutdown")
        public ExecutorService llmExecutor() {
            return Executors.newFixedThreadPool(4);
        }

        @Bean
        public RestTemplateConfig restTemplateConfig() {
            return new RestTemplateConfig();
        }

        @Bean
        public EmbeddingsClient embeddingsClient() {
            return Mockito.mock(EmbeddingsClient.class);
        }

        @Bean
        public EmbeddingService embeddingService(EmbeddingsClient embeddingsClient) {
            return new EmbeddingService(embeddingsClient);
        }

        @Bean
        public RDFService rdfService(RestTemplateConfig restTemplateConfig) {
            return new RDFService(restTemplateConfig);
        }

        @Bean
        public TerminologyLookupService terminologyLookupService(
                RDFService rdfService,
                EmbeddingsClient embeddingsClient,
                @Qualifier("llmExecutor") ObjectProvider<ExecutorService> exec,
                @Value("${snowstorm.enabled:true}") boolean snowstormEnabled,
                @Value("${snowstorm.timeout:30}") int snowstormTimeoutSeconds,
                @Value("${snowstorm.lookup.retries:1}") int snowstormLookupRetries,
                @Value("${snowstorm.lookup.retryBackoffMs:150}") long retryBackoffMs,
                @Value("${terminology.fallback.enabled:false}") boolean fallbackEnabled) {
            TerminologyLookupService svc =
                    new TerminologyLookupService(rdfService, embeddingsClient, exec);
            ReflectionTestUtils.setField(svc, "snowstormEnabled",             snowstormEnabled);
            ReflectionTestUtils.setField(svc, "snowstormTimeoutSeconds",       snowstormTimeoutSeconds);
            ReflectionTestUtils.setField(svc, "snowstormLookupRetries",        snowstormLookupRetries);
            ReflectionTestUtils.setField(svc, "snowstormLookupRetryBackoffMs", retryBackoffMs);
            ReflectionTestUtils.setField(svc, "fallbackEnabled",               fallbackEnabled);
            return svc;
        }

        @Bean
        public OpenMedTerminologyService openMedTerminologyService(
                RDFService rdfService,
                @Value("${openmed.service.url:http://localhost:8002}") String url,
                @Value("${openmed.enabled:true}") boolean enabled,
                @Value("${openmed.batch.size:5}") int batchSize,
                @Value("${openmed.timeout.ms:30000}") int timeoutMs,
                @Value("${snowstorm.enabled:true}") boolean snowstormEnabled) {
            OpenMedTerminologyService svc = new OpenMedTerminologyService(rdfService);
            ReflectionTestUtils.setField(svc, "openmedUrl",          url);
            ReflectionTestUtils.setField(svc, "enabled",             enabled);
            ReflectionTestUtils.setField(svc, "configuredBatchSize", batchSize);
            ReflectionTestUtils.setField(svc, "timeoutMs",           timeoutMs);
            ReflectionTestUtils.setField(svc, "snowstormEnabled",    snowstormEnabled);
            return svc;
        }

        @Bean
        public ChatModel ollamaChatModel() {
            OllamaApi api = OllamaApi.builder().baseUrl("http://localhost:11434").build();
            org.springframework.ai.ollama.api.OllamaChatOptions options =
                    org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                            .model("llama2").temperature(0.7).build();
            org.springframework.ai.model.tool.ToolCallingManager tcm =
                    org.springframework.ai.model.tool.ToolCallingManager.builder().build();
            org.springframework.ai.ollama.management.ModelManagementOptions mmo =
                    org.springframework.ai.ollama.management.ModelManagementOptions.builder().build();
            return new OllamaChatModel(api, options, tcm,
                    io.micrometer.observation.ObservationRegistry.NOOP, mmo);
        }

        @Bean
        public LLMTextGenerator llmTextGenerator(
                ObjectProvider<ChatModel> chatModelProvider,
                @Value("${llm.enabled:false}") boolean llmEnabled) {
            return new LLMTextGenerator(chatModelProvider, llmEnabled);
        }

        @Bean
        public DescriptionService descriptionGenerator(
                LLMTextGenerator llmTextGenerator, ExecutorService llmExecutor) {
            return new DescriptionService(llmTextGenerator, llmExecutor);
        }

        @Bean
        public TerminologyTermInferenceService terminologyTermInferenceService(
                LLMTextGenerator llmTextGenerator,
                @Qualifier("llmExecutor") ExecutorService llmExecutor) {
            return new TerminologyTermInferenceService(llmTextGenerator, llmExecutor);
        }

        @Bean
        public ValueMappingBuilder valueMappingBuilder(EmbeddingService embeddingService) {
            return new ValueMappingBuilder(embeddingService);
        }

        @Bean
        public MappingConfig.MappingServiceSettings mappingSettings() {
            return new MappingConfig.MappingServiceSettings(
                    60, 120, 0.33, 0.56, 6, 40, 10, 0.22, 4, 2, 2, 6);
        }

        @Bean
        public MappingService mappingService(
                EmbeddingService embeddingService,
                TerminologyLookupService terminologyLookupService,
                TerminologyTermInferenceService terminologyTermInferenceService,
                OpenMedTerminologyService openMedTerminologyService,
                DescriptionService descriptionGenerator,
                ValueMappingBuilder valueMappingBuilder,
                ObjectMapper objectMapper,
                MappingConfig.MappingServiceSettings mappingSettings) {
            return new MappingService(embeddingService, terminologyLookupService,
                    terminologyTermInferenceService, openMedTerminologyService,
                    descriptionGenerator, valueMappingBuilder, objectMapper, mappingSettings);
        }
    }

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    private static final Logger logger =
            LoggerFactory.getLogger(OpenMedTerminologyReportTest.class);

    private static final Pattern SNOMED_FORMAT = Pattern.compile("^(.+ \\| )?\\d{6,}$");
    private static final ObjectMapper MAPPER   = new ObjectMapper();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final String OPENMED_HEALTH_URL  = "http://localhost:8002/health";
    private static final String SNOWSTORM_API_URL   = "http://localhost:9100";
    private static final String ES_URL              = "http://localhost:9200";
    private static final String BRIDGE_TYPES_URL    = "http://localhost:8000/types";

    private static final String ES_CONTAINER_NAME = "elasticsearch-test";
    private static final String SN_CONTAINER_NAME = "snowstorm-test";
    private static final String DOCKER_NETWORK    = "snowstorm-net";

    private static final int ES_STARTUP_TIMEOUT_S        = 90;
    private static final int SNOWSTORM_STARTUP_TIMEOUT_S = 180;
    private static final int OPENMED_HEALTH_TIMEOUT_S    = 30;
    private static final int OPENMED_WARMUP_TIMEOUT_S    = 30;

    private static boolean snowstormAvailable = false;
    private static boolean openMedAvailable   = false;
    private static HttpServer rdfBridge       = null;

    /** Seeded concepts: lowercase search phrase → "conceptId|label". */
    private static final Map<String, String> seededTerms = new LinkedHashMap<>();

    // -----------------------------------------------------------------------
    // @BeforeAll / @AfterAll
    // -----------------------------------------------------------------------

    @BeforeAll
    static void startFullStack() throws Exception {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  OpenMed + Snowstorm Live Test – stack startup       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        startSnowstormStack();

        if (snowstormAvailable) {
            startRdfBuilderBridge();
        }

        startOpenMedService();

        System.out.println("\n  ── Stack summary ──────────────────────────────────────");
        System.out.printf("  Snowstorm:   %s  (%d concepts seeded)%n",
                snowstormAvailable ? "✓ UP" : "✗ DOWN", seededTerms.size());
        System.out.printf("  rdf-bridge:  %s%n",
                probeHttp(BRIDGE_TYPES_URL, 500) ? "✓ UP  (port 8000)" : "✗ DOWN");
        System.out.printf("  OpenMed NER: %s%n",
                openMedAvailable ? "✓ UP  (port 8002)" : "✗ DOWN");
        System.out.println();
    }

    @AfterAll
    static void tearDown() {
        if (rdfBridge != null) {
            try { rdfBridge.stop(0); } catch (Exception ignored) {}
            System.out.println("[OpenMedReport] rdf-builder bridge stopped.");
        }
    }

    // ── 1. Snowstorm Docker stack ─────────────────────────────────────────────

    private static void startSnowstormStack() throws Exception {
        if (!dockerAvailable()) {
            System.out.println("  Docker not available – Snowstorm will not be started.");
            return;
        }

        runDockerQuiet("docker", "network", "create", DOCKER_NETWORK);

        // Elasticsearch
        String esStatus = dockerContainerStatus(ES_CONTAINER_NAME);
        if ("running".equals(esStatus)) {
            System.out.println("  Elasticsearch: ✓ already running (" + ES_CONTAINER_NAME + ")");
        } else if ("exited".equals(esStatus)) {
            System.out.println("  Elasticsearch: restarting stopped container…");
            runDocker("docker", "start", ES_CONTAINER_NAME);
        } else {
            System.out.println("  Elasticsearch: creating container…");
            runDocker("docker", "run", "-d",
                    "--name", ES_CONTAINER_NAME,
                    "--network", DOCKER_NETWORK,
                    "-p", "9200:9200",
                    "-e", "discovery.type=single-node",
                    "-e", "xpack.security.enabled=false",
                    "-e", "ES_JAVA_OPTS=-Xms2g -Xmx2g",
                    "docker.elastic.co/elasticsearch/elasticsearch:8.11.1");
        }

        if (!waitForHttp(ES_URL + "/", ES_STARTUP_TIMEOUT_S, "Elasticsearch")) {
            System.out.println("  ✗ Elasticsearch not ready – Snowstorm skipped.");
            return;
        }

        // Snowstorm
        String snStatus = dockerContainerStatus(SN_CONTAINER_NAME);
        if ("running".equals(snStatus)) {
            System.out.println("  Snowstorm:     ✓ already running (" + SN_CONTAINER_NAME + ")");
        } else if ("exited".equals(snStatus)) {
            System.out.println("  Snowstorm:     restarting stopped container…");
            runDocker("docker", "start", SN_CONTAINER_NAME);
        } else {
            System.out.println("  Snowstorm:     creating container (takes ~60-120 s)…");
            runDocker("docker", "run", "-d",
                    "--name", SN_CONTAINER_NAME,
                    "--network", DOCKER_NETWORK,
                    "-p", "9100:8080",
                    "--entrypoint", "java",
                    "snomedinternational/snowstorm:latest",
                    "-Xms1g", "-Xmx2g",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "-cp", "@/app/jib-classpath-file",
                    "org.snomed.snowstorm.SnowstormApplication",
                    "--elasticsearch.urls=http://" + ES_CONTAINER_NAME + ":9200",
                    "--spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration");
        }

        if (!waitForHttp(SNOWSTORM_API_URL + "/version", SNOWSTORM_STARTUP_TIMEOUT_S, "Snowstorm")) {
            System.out.println("  ✗ Snowstorm not ready within " + SNOWSTORM_STARTUP_TIMEOUT_S + "s.");
            return;
        }

        seedSnowstormConcepts();
        snowstormAvailable = !seededTerms.isEmpty();
    }

    // ── 1b. Seed medical concepts ─────────────────────────────────────────────

    private static void seedSnowstormConcepts() {
        Map<String, String> termsToBuild = new LinkedHashMap<>();
        // key = lowercase search phrase OpenMed produces, value = FSN label for Snowstorm
        // Standard medical terms
        termsToBuild.put("hypertension",               "Hypertension");
        termsToBuild.put("stroke",                      "Stroke");
        termsToBuild.put("diabetes mellitus",           "Diabetes mellitus");
        termsToBuild.put("myocardial infarction",       "Myocardial infarction");
        termsToBuild.put("aspirin",                     "Aspirin");
        termsToBuild.put("metformin",                   "Metformin");
        termsToBuild.put("warfarin",                    "Warfarin");
        termsToBuild.put("lisinopril",                  "Lisinopril");
        termsToBuild.put("male",                        "Male");
        termsToBuild.put("female",                      "Female");
        termsToBuild.put("rehabilitation",              "Rehabilitation");
        termsToBuild.put("deceased",                    "Deceased");
        termsToBuild.put("home",                        "Home");
        termsToBuild.put("diagnosis",                   "Diagnosis");
        termsToBuild.put("discharge status",            "Discharge status");
        // Barthel-scale numeric context terms – produced by the Python service as
        // "col_search_term value" (e.g. "toilet 0", "toilet 5", "toilet 10")
        termsToBuild.put("toilet 0",                    "Toilet 0 (dependent)");
        termsToBuild.put("toilet 5",                    "Toilet 5 (needs help)");
        termsToBuild.put("toilet 10",                   "Toilet 10 (independent)");
        termsToBuild.put("barthel index 0",             "Barthel index 0");
        termsToBuild.put("barthel index 5",             "Barthel index 5");
        termsToBuild.put("barthel index 10",            "Barthel index 10");
        // Education level context terms – produced as "high school education", etc.
        termsToBuild.put("high school education",       "High school education");
        termsToBuild.put("primary school education",    "Primary school education");
        termsToBuild.put("university education",        "University education");

        System.out.println("  Seeding Snowstorm with medical concepts…");
        for (Map.Entry<String, String> entry : termsToBuild.entrySet()) {
            String phrase = entry.getKey();
            String label  = entry.getValue();
            String existing = searchSnowstormForExact(phrase);
            if (existing != null) {
                seededTerms.put(phrase, existing);
            } else {
                String conceptId = createSnowstormConcept(label);
                if (conceptId != null) {
                    seededTerms.put(phrase, conceptId + "|" + label);
                }
            }
        }
        System.out.printf("  Seeded %d / %d concepts%n", seededTerms.size(), termsToBuild.size());
    }

    private static String searchSnowstormForExact(String phrase) {
        try {
            String url = SNOWSTORM_API_URL + "/MAIN/concepts?term="
                    + URLEncoder.encode(phrase, StandardCharsets.UTF_8)
                    + "&activeFilter=true&limit=1";
            String body   = httpGet(url, 5_000);
            JsonNode root  = MAPPER.readTree(body);
            JsonNode items = root.path("items");
            if (!items.isArray() || items.isEmpty()) return null;
            JsonNode item = items.get(0);
            String cid  = item.path("conceptId").asText("");
            JsonNode pt  = item.path("pt");
            JsonNode fsn = item.path("fsn");
            String lbl = pt.has("term") ? pt.path("term").asText("")
                    : fsn.path("term").asText("");
            return (!cid.isEmpty() && !lbl.isEmpty()) ? cid + "|" + lbl : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String createSnowstormConcept(String label) {
        try {
            String payload = MAPPER.writeValueAsString(Map.of(
                    "active",        true,
                    "moduleId",      "900000000000012004",
                    "descriptions",  List.of(Map.of(
                            "active",             true,
                            "moduleId",           "900000000000012004",
                            "typeId",             "900000000000003001",
                            "term",               label,
                            "lang",               "en",
                            "caseSignificanceId", "900000000000448009",
                            "acceptabilityMap",   Map.of("900000000000509007", "PREFERRED")
                    )),
                    "relationships", List.of(),
                    "classAxioms",   List.of()
            ));
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(SNOWSTORM_API_URL + "/browser/MAIN/concepts"))
                            .timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                String cid = MAPPER.readTree(resp.body()).path("conceptId").asText("");
                if (!cid.isEmpty()) { logger.debug("Created '{}' → {}", label, cid); return cid; }
            }
        } catch (Exception e) {
            logger.warn("[OpenMedReport] Could not create concept '{}': {}", label, e.getMessage());
        }
        return null;
    }

    // ── 2. Inline rdf-builder bridge (Java HttpServer on port 8000) ──────────

    private static void startRdfBuilderBridge() throws Exception {
        if (probeHttp(BRIDGE_TYPES_URL, 500)) {
            System.out.println("  rdf-builder bridge: ✓ already running on port 8000");
            return;
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);

            // /types – health-check used by RDFService probe
            server.createContext("/types", exchange -> {
                byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            });

            // /term/<query> – forward to Snowstorm and format as ["conceptId|label", …]
            server.createContext("/term/", exchange -> {
                String path    = exchange.getRequestURI().getRawPath();
                String rawQuery = path.length() > "/term/".length()
                        ? path.substring("/term/".length()) : "";
                String decoded = URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
                List<String> codes = querySnowstormForCodes(decoded, 5);
                byte[] resp;
                try { resp = MAPPER.writeValueAsBytes(codes); }
                catch (Exception ex) { resp = "[]".getBytes(StandardCharsets.UTF_8); }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            });

            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            rdfBridge = server;
            System.out.println("  rdf-builder bridge: ✓ started on port 8000"
                    + " (Snowstorm proxy: " + SNOWSTORM_API_URL + ")");
        } catch (IOException e) {
            System.out.println("  ✗ Could not start rdf-builder bridge: " + e.getMessage());
        }
    }

    private static List<String> querySnowstormForCodes(String term, int limit) {
        try {
            String url   = SNOWSTORM_API_URL + "/MAIN/concepts?term="
                    + URLEncoder.encode(term, StandardCharsets.UTF_8)
                    + "&activeFilter=true&limit=" + limit;
            String body  = httpGet(url, 8_000);
            JsonNode items = MAPPER.readTree(body).path("items");
            if (!items.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            for (JsonNode item : items) {
                String cid  = item.path("conceptId").asText("");
                JsonNode pt  = item.path("pt");
                JsonNode fsn = item.path("fsn");
                String lbl  = pt.has("term") ? pt.path("term").asText("")
                        : fsn.path("term").asText("");
                if (!cid.isEmpty() && !lbl.isEmpty()) result.add(cid + "|" + lbl);
            }
            return result;
        } catch (Exception e) {
            logger.debug("[Bridge] Snowstorm query error for '{}': {}", term, e.getMessage());
            return List.of();
        }
    }

    // ── 3. OpenMed Python service ─────────────────────────────────────────────

    private static void startOpenMedService() throws Exception {
        System.out.println("  OpenMed NER service:");
        if (probeHttp(OPENMED_HEALTH_URL, 1_000)) {
            System.out.println("    ✓ already running");
            openMedAvailable = warmUpOpenMed();
            System.out.println("    Model warm-up: " + (openMedAvailable ? "✓ ready" : "✗ not responding"));
            return;
        }

        Path projectRoot = Paths.get("mediata-openmed").toAbsolutePath();
        if (!projectRoot.toFile().isDirectory()) {
            System.out.println("    ⚠ mediata-openmed/ not found – start manually: "
                    + "cd mediata-openmed && uvicorn main:app --port 8002");
            return;
        }

        Map<String, String> env = new HashMap<>(System.getenv());
        org.taniwha.util.PythonLauncherUtil.loadDotEnv(projectRoot.resolve(".env").toFile(), env);

        Path venvDir = projectRoot.resolve(".venv");
        if (venvDir.toFile().isDirectory()) {
            System.out.println("    venv found – launching service directly…");
            String cmd = "source " + venvDir.toAbsolutePath() + "/bin/activate"
                    + " && uvicorn main:app --host 0.0.0.0 --port 8002";
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd)
                    .directory(projectRoot.toFile()).inheritIO();
            pb.environment().putAll(env);
            Process proc = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("[OpenMedReport] Stopping OpenMed service");
                proc.destroy();
            }));
        } else {
            System.out.println("    No venv – running full setup…");
            java.io.File venvFile = org.taniwha.util.PythonLauncherUtil.ensureVirtualEnv(projectRoot, env);
            java.io.File python   = org.taniwha.util.PythonLauncherUtil.pickPython(venvFile);
            org.taniwha.util.PythonLauncherUtil.ensureDependencies(
                    projectRoot.toFile(), env, python,
                    org.taniwha.util.PythonLauncherUtil.parseDeps(
                            "fastapi, uvicorn[standard], transformers, torch"));
            org.taniwha.util.PythonLauncherUtil.launchAsync(projectRoot.toFile(), env,
                    "source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8002");
        }

        long healthDeadline = System.currentTimeMillis() + OPENMED_HEALTH_TIMEOUT_S * 1_000L;
        while (System.currentTimeMillis() < healthDeadline) {
            if (probeHttp(OPENMED_HEALTH_URL, 1_000)) { openMedAvailable = true; break; }
            System.out.print(".");
            Thread.sleep(1_000);
        }
        System.out.println();
        System.out.println("    /health: " + (openMedAvailable ? "✓ UP"
                : "✗ not ready in " + OPENMED_HEALTH_TIMEOUT_S + "s"));
        if (openMedAvailable) {
            openMedAvailable = warmUpOpenMed();
            System.out.println("    warm-up: " + (openMedAvailable ? "✓ ready"
                    : "✗ not responding to /infer_batch"));
        }
    }

    // -----------------------------------------------------------------------
    // Test dataset
    // -----------------------------------------------------------------------

    private static List<ColumnRecord> testColumns() {
        return List.of(
            // Medical diagnosis column with named values
            new ColumnRecord("Diagnosis",
                    List.of("Hypertension", "Diabetes Mellitus", "Stroke", "Myocardial infarction")),
            new ColumnRecord("PatientGender",
                    List.of("Male", "Female", "Other")),
            new ColumnRecord("BloodPressureSystolic",
                    List.of("120", "140", "160")),
            new ColumnRecord("NIHSSScore",
                    List.of("0", "5", "10", "15", "25")),
            // Barthel ADL scale: numeric values (0/5/10) that must not be omitted
            // – the Python service uses column context to generate "toilet 0", "toilet 5", etc.
            new ColumnRecord("Toilet",
                    List.of("0", "5", "10")),
            new ColumnRecord("MedicationType",
                    List.of("Aspirin", "Metformin", "Warfarin", "Lisinopril")),
            new ColumnRecord("DischargeStatus",
                    List.of("Home", "Rehabilitation", "Long-term care", "Deceased")),
            // Education column: "High school" is a short/common phrase that must get terminology
            new ColumnRecord("Education",
                    List.of("Primary school", "High school", "University"))
        );
    }

    // -----------------------------------------------------------------------
    // Autowired services
    // -----------------------------------------------------------------------

    @org.springframework.beans.factory.annotation.Autowired
    private OpenMedTerminologyService openMedTerminologyService;

    @org.springframework.beans.factory.annotation.Autowired
    private TerminologyLookupService terminologyLookupService;

    // -----------------------------------------------------------------------
    // Test
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Full pipeline: OpenMed NER → Snowstorm SNOMED – terminology must be non-empty for known medical terms")
    void generates_openmed_terminology_report() throws Exception {
        assumeTrue(openMedAvailable,
                "Skipping: OpenMed service not reachable at " + OPENMED_HEALTH_URL);

        List<ColumnRecord> columns = testColumns();

        // ── Step 1: OpenMed NER ───────────────────────────────────────────────
        System.out.println("\n=== Step 1: OpenMed NER inference ===");
        long t0 = System.currentTimeMillis();
        List<OpenMedTerminologyService.InferredTerm> inferred =
                openMedTerminologyService.inferBatch(columns);
        long inferMs = System.currentTimeMillis() - t0;
        System.out.printf("  %d columns → %d inferred terms in %d ms%n",
                columns.size(), inferred.size(), inferMs);

        assertNotNull(inferred, "inferBatch must not return null");
        assumeTrue(!inferred.isEmpty(),
                "OpenMed returned no InferredTerms – model may not be functional in this environment");

        for (OpenMedTerminologyService.InferredTerm t : inferred) {
            assertFalse(t.colSearchTerm().isBlank(),
                    "colSearchTerm must not be blank for col='" + t.colKey() + "'");
            assertFalse(SNOMED_FORMAT.matcher(t.colSearchTerm()).matches(),
                    "colSearchTerm must not look like a SNOMED code: '" + t.colSearchTerm() + "'");
            System.out.printf("  col='%s' → '%s'%n", t.colKey(), t.colSearchTerm());
            if (t.valueSearchTerms() != null) {
                t.valueSearchTerms().forEach((v, s) ->
                        System.out.printf("    val='%s' → '%s'%n", v, s));
            }
        }

        // ── Step 2: Build lookup requests ────────────────────────────────────
        System.out.println("\n=== Step 2: building Snowstorm lookup requests ===");
        Map<String, String> colLookupKeys = new LinkedHashMap<>();
        Map<String, String> valLookupKeys = new LinkedHashMap<>();
        List<TerminologyLookupService.TerminologyRequest> requests = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (OpenMedTerminologyService.InferredTerm t : inferred) {
            String colKey = t.colKey() + "|" + t.colSearchTerm();
            colLookupKeys.put(t.colKey(), colKey);
            if (seen.add(colKey))
                requests.add(new TerminologyLookupService.TerminologyRequest(colKey, null));
            if (t.valueSearchTerms() != null) {
                t.valueSearchTerms().forEach((rawVal, valTerm) -> {
                    String vKey = t.colKey() + "|" + valTerm;
                    valLookupKeys.put(t.colKey() + "|" + rawVal, vKey);
                    if (seen.add(vKey))
                        requests.add(new TerminologyLookupService.TerminologyRequest(vKey, t.colKey()));
                });
            }
        }
        System.out.printf("  %d lookup request(s)%n", requests.size());

        // ── Step 3: Snowstorm lookup ──────────────────────────────────────────
        System.out.println("\n=== Step 3: Snowstorm SNOMED lookup ===");
        if (!snowstormAvailable)
            System.out.println("  ⚠ Snowstorm not available – all SNOMED results will be empty");
        long t1 = System.currentTimeMillis();
        Map<String, String> snomedResults =
                terminologyLookupService.batchLookupTerminology(requests);
        long lookupMs = System.currentTimeMillis() - t1;
        System.out.printf("  %d result(s) in %d ms%n", snomedResults.size(), lookupMs);

        // ── Step 4: Apply SNOMED filter ───────────────────────────────────────
        System.out.println("\n=== Step 4: SNOMED format filter ===");
        Map<String, TermResult> finalResults = new LinkedHashMap<>();
        for (OpenMedTerminologyService.InferredTerm t : inferred) {
            String rawSnomed  = snomedResults.getOrDefault(
                    colLookupKeys.getOrDefault(t.colKey(), ""), "");
            String terminology = resolveToSnomed(rawSnomed);
            Map<String, String> valueTerm = new LinkedHashMap<>();
            if (t.valueSearchTerms() != null) {
                t.valueSearchTerms().forEach((rawVal, valTerm) -> {
                    String vKey = valLookupKeys.getOrDefault(t.colKey() + "|" + rawVal, "");
                    String vSnomed = resolveToSnomed(snomedResults.getOrDefault(vKey, ""));
                    valueTerm.put(rawVal, vSnomed);
                    System.out.printf("    val='%-25s search='%-30s SNOMED='%s'%n",
                            rawVal + "'", valTerm + "'", vSnomed);
                });
            }
            finalResults.put(t.colKey(), new TermResult(t.colKey(), t.colSearchTerm(),
                    terminology, valueTerm));
            System.out.printf("  col='%-22s search='%-30s SNOMED='%s'%n",
                    t.colKey() + "'", t.colSearchTerm() + "'", terminology);
        }

        // ── Step 5: Format violations ─────────────────────────────────────────
        System.out.println("\n=== Step 5: SNOMED format assertions ===");
        int snomedCount = 0, emptyCount = 0;
        List<String> violations = new ArrayList<>();
        for (TermResult r : finalResults.values()) {
            String v = validateTerminologyField(r.colKey, r.colSearchTerm, r.terminology, violations);
            if (!v.isEmpty()) snomedCount++; else emptyCount++;
            for (Map.Entry<String, String> e : r.valueTerminology.entrySet()) {
                String vv = validateTerminologyField(r.colKey + "/" + e.getKey(), "", e.getValue(), violations);
                if (!vv.isEmpty()) snomedCount++; else emptyCount++;
            }
        }
        System.out.printf("  %d SNOMED, %d empty%n", snomedCount, emptyCount);
        violations.forEach(vv -> System.out.println("  ✗ " + vv));
        assertTrue(violations.isEmpty(), "Format violations:\n" + String.join("\n", violations));

        // ── Step 6: Write report ──────────────────────────────────────────────
        Path outDir = Paths.get("target", "openmed-report");
        Files.createDirectories(outDir);
        String stamp = LocalDateTime.now().format(TS_FMT);
        Path jsonOut = outDir.resolve("openmed-report-" + stamp + ".json");
        Path mdOut   = outDir.resolve("openmed-report-" + stamp + ".md");
        writeJsonReport(jsonOut, finalResults);
        writeMarkdownReport(mdOut, finalResults, inferMs, lookupMs);
        assertTrue(Files.exists(jsonOut), "JSON report must exist");
        assertTrue(Files.exists(mdOut),   "Markdown report must exist");
        System.out.println("\n  Reports: " + jsonOut + "\n           " + mdOut);

        // ── Step 7: Non-empty SNOMED for known medical value terms ────────────
        System.out.println("\n=== Step 7: non-empty terminology assertions ===");
        if (snowstormAvailable) {
            // These value terms must resolve to SNOMED codes because:
            //   a) OpenMed NER produces valid search phrases for them
            //   b) Matching concepts were seeded into Snowstorm in @BeforeAll
            String[] mustHaveSnomed = {
                    "Hypertension", "Stroke", "Diabetes Mellitus", "Aspirin", "Warfarin"};
            for (String valueName : mustHaveSnomed) {
                String terminology = findValueTerminology(finalResults, valueName);
                assertNotNull(terminology,
                        "Value '" + valueName + "' was not found in the inferred terms");
                assertFalse(terminology.isEmpty(),
                        "Terminology for '" + valueName + "' must NOT be empty when Snowstorm is "
                        + "running – concept was seeded and OpenMed should produce a valid search term.");
                assertTrue(SNOMED_FORMAT.matcher(terminology).matches(),
                        "Terminology for '" + valueName + "' must be valid SNOMED format " +
                        "(label | code), got: '" + terminology + "'");
                System.out.printf("  ✓ %-25s → '%s'%n", valueName, terminology);
            }

            // Numeric values in the Toilet column must now be present (not filtered out)
            // because the Python service generates contextual phrases like "toilet 0"
            OpenMedTerminologyService.InferredTerm toiletTerm = inferred.stream()
                    .filter(t -> "Toilet".equalsIgnoreCase(t.colKey()))
                    .findFirst().orElse(null);
            if (toiletTerm != null && toiletTerm.valueSearchTerms() != null) {
                for (String numVal : List.of("0", "5", "10")) {
                    String searchPhrase = toiletTerm.valueSearchTerms().get(numVal);
                    assertNotNull(searchPhrase,
                            "Toilet value '" + numVal + "' must produce a search term "
                            + "(numeric values must NOT be omitted when column context is available)");
                    assertFalse(searchPhrase.isBlank(),
                            "Toilet value '" + numVal + "' search term must not be blank");
                    assertTrue(searchPhrase.toLowerCase().contains("toilet"),
                            "Toilet value '" + numVal + "' search term must contain 'toilet', got: '"
                            + searchPhrase + "'");
                    System.out.printf("  ✓ Toilet %-3s → search='%s'%n", numVal, searchPhrase);
                }
            } else {
                System.out.println("  ⚠ Toilet column not found in inferred terms – numeric assertion skipped");
            }

            // Education values must produce non-empty search phrases
            OpenMedTerminologyService.InferredTerm eduTerm = inferred.stream()
                    .filter(t -> "Education".equalsIgnoreCase(t.colKey()))
                    .findFirst().orElse(null);
            if (eduTerm != null && eduTerm.valueSearchTerms() != null) {
                String hsPhrase = eduTerm.valueSearchTerms().get("High school");
                if (hsPhrase != null) {
                    assertFalse(hsPhrase.isBlank(),
                            "'High school' search term must not be blank");
                    System.out.printf("  ✓ Education 'High school' → search='%s'%n", hsPhrase);
                }
            }
        }

        // ── Step 8: Model-output quality ─────────────────────────────────────
        System.out.println("\n=== Step 8: model-output quality assertions ===");
        assertModelOutputQuality(inferred);

        System.out.printf("%n  ✓ Pipeline passed: %d SNOMED, %d empty, 0 format violations%n",
                snomedCount, emptyCount);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String findValueTerminology(Map<String, TermResult> results, String valueName) {
        for (TermResult r : results.values()) {
            if (r.valueTerminology.containsKey(valueName))
                return r.valueTerminology.get(valueName);
            for (Map.Entry<String, String> e : r.valueTerminology.entrySet())
                if (e.getKey().equalsIgnoreCase(valueName)) return e.getValue();
        }
        return null;
    }

    private static String resolveToSnomed(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return SNOMED_FORMAT.matcher(raw.trim()).matches() ? raw.trim() : "";
    }

    private static String validateTerminologyField(
            String location, String searchTerm, String terminology, List<String> violations) {
        if (terminology == null || terminology.isEmpty()) return "";
        if (terminology.startsWith("CONCEPT_"))
            violations.add("[" + location + "] synthetic fallback must be filtered: " + terminology);
        else if (!SNOMED_FORMAT.matcher(terminology).matches())
            violations.add("[" + location + "] '" + searchTerm + "' → '" + terminology
                    + "' does not match SNOMED format (expected 'label | code' or bare code)");
        return terminology;
    }

    private void assertModelOutputQuality(List<OpenMedTerminologyService.InferredTerm> inferred) {
        for (OpenMedTerminologyService.InferredTerm t : inferred) {
            assertFalse(t.colSearchTerm().isBlank(),
                    "col='" + t.colKey() + "': search term must not be blank");
            assertFalse(SNOMED_FORMAT.matcher(t.colSearchTerm()).matches(),
                    "col='" + t.colKey() + "': search term must not look like SNOMED");
            System.out.printf("  [COL] %-25s → '%s' ✓%n", t.colKey(), t.colSearchTerm());
        }

        record Expected(String valueName, String mustContain) {}
        List<Expected> checks = List.of(
                new Expected("Hypertension",    "hypertension"),
                new Expected("Stroke",          "stroke"),
                new Expected("Diabetes Mellitus","diabetes"),
                new Expected("Aspirin",         "aspirin"),
                new Expected("Warfarin",        "warfarin"),
                new Expected("Female",          "female")
        );
        for (Expected exp : checks) {
            String phrase = null;
            outer:
            for (OpenMedTerminologyService.InferredTerm t : inferred) {
                if (t.valueSearchTerms() == null) continue;
                for (Map.Entry<String, String> e : t.valueSearchTerms().entrySet()) {
                    if (e.getKey().equalsIgnoreCase(exp.valueName())) { phrase = e.getValue(); break outer; }
                }
            }
            if (phrase == null) {
                System.out.printf("  [VAL] %-20s → (not in inferred terms)%n", exp.valueName());
                continue;
            }
            assertFalse(phrase.isBlank(), "phrase must not be blank for '" + exp.valueName() + "'");
            assertFalse(SNOMED_FORMAT.matcher(phrase).matches(),
                    "'" + exp.valueName() + "': search term must not look like SNOMED: " + phrase);
            assertTrue(phrase.toLowerCase().contains(exp.mustContain()),
                    "'" + exp.valueName() + "': expected '" + exp.mustContain()
                            + "' in search term, got '" + phrase + "'");
            System.out.printf("  [VAL] %-20s → '%s' ✓%n", exp.valueName(), phrase);
        }

        // Verify that numeric values in 'Toilet' column produce contextual search terms
        // (not just the raw number), and that those terms pass isValidTerm()
        for (OpenMedTerminologyService.InferredTerm t : inferred) {
            if (!"Toilet".equalsIgnoreCase(t.colKey())) continue;
            if (t.valueSearchTerms() == null) continue;
            for (String nv : List.of("0", "5", "10")) {
                String term = t.valueSearchTerms().get(nv);
                if (term != null) {
                    assertTrue(openMedTerminologyService.isValidTerm(term),
                            "col='Toilet' val='" + nv
                                    + "': numeric value WITH column context must be a valid search term, got: '"
                                    + term + "'");
                    assertTrue(term.toLowerCase().contains("toilet"),
                            "col='Toilet' val='" + nv + "': contextual term must contain 'toilet', got: '"
                                    + term + "'");
                    System.out.printf("  [NUM] Toilet %-3s → '%s' ✓%n", nv, term);
                }
            }
        }

        // BloodPressureSystolic numeric values should also have contextual terms
        for (OpenMedTerminologyService.InferredTerm t : inferred) {
            if (!"BloodPressureSystolic".equalsIgnoreCase(t.colKey())) continue;
            if (t.valueSearchTerms() == null) continue;
            for (String nv : List.of("120", "140", "160")) {
                String term = t.valueSearchTerms().get(nv);
                if (term != null) {
                    assertTrue(openMedTerminologyService.isValidTerm(term),
                            "col='BloodPressureSystolic' val='" + nv
                                    + "': numeric value WITH column context must be a valid search term, got: '"
                                    + term + "'");
                    System.out.printf("  [NUM] BloodPressureSystolic %-3s → '%s' ✓%n", nv, term);
                }
            }
        }
        System.out.println("  ✓ All model-output quality assertions passed");
    }

    // -----------------------------------------------------------------------
    // Report writers
    // -----------------------------------------------------------------------

    private static void writeJsonReport(Path out, Map<String, TermResult> results) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TermResult r : results.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("column",      r.colKey);
            row.put("searchTerm",  r.colSearchTerm);
            row.put("terminology", r.terminology);
            row.put("isSnomed",    !r.terminology.isEmpty());
            Map<String, Object> vals = new LinkedHashMap<>();
            r.valueTerminology.forEach((v, t) -> vals.put(v, Map.of(
                    "terminology", t, "isSnomed", !t.isEmpty())));
            row.put("values", vals);
            rows.add(row);
        }
        try (OutputStream os = Files.newOutputStream(out,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            os.write(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(rows));
        }
    }

    private static void writeMarkdownReport(Path out, Map<String, TermResult> results,
                                             long inferMs, long lookupMs) throws IOException {
        int snomedCount = (int) results.values().stream().filter(r -> !r.terminology.isEmpty()).count();
        int valSnomedCount = (int) results.values().stream()
                .flatMap(r -> r.valueTerminology.values().stream())
                .filter(t -> !t.isEmpty()).count();
        StringBuilder sb = new StringBuilder()
                .append("# OpenMed Terminology Report\n\n| | |\n|---|---|\n")
                .append("| Generated | ").append(LocalDateTime.now()).append(" |\n")
                .append("| OpenMed | ").append(openMedAvailable ? "✓ UP" : "✗ DOWN").append(" |\n")
                .append("| Snowstorm | ").append(snowstormAvailable
                        ? "✓ UP (" + seededTerms.size() + " concepts seeded)" : "✗ DOWN")
                .append(" |\n")
                .append("| Inference | ").append(inferMs).append(" ms |\n")
                .append("| Lookup | ").append(lookupMs).append(" ms |\n")
                .append("| Column SNOMED | ").append(snomedCount).append(" / ")
                .append(results.size()).append(" |\n")
                .append("| Value SNOMED | ").append(valSnomedCount).append(" |\n\n")
                .append("## Columns\n\n| Column | Search term | SNOMED |\n|--------|------------|--------|\n");
        results.forEach((k, r) -> sb.append("| `").append(r.colKey).append("` | `")
                .append(r.colSearchTerm).append("` | `")
                .append(r.terminology.isEmpty() ? "—" : r.terminology).append("` |\n"));
        sb.append("\n## Values\n\n");
        results.forEach((k, r) -> {
            if (r.valueTerminology.isEmpty()) return;
            sb.append("### `").append(r.colKey).append("`\n\n| Value | SNOMED |\n|-------|--------|\n");
            r.valueTerminology.forEach((v, t) ->
                    sb.append("| `").append(v).append("` | `")
                      .append(t.isEmpty() ? "—" : t).append("` |\n"));
            sb.append("\n");
        });
        try (OutputStream os = Files.newOutputStream(out,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // -----------------------------------------------------------------------
    // Infrastructure utilities
    // -----------------------------------------------------------------------

    private static String httpGet(String url, int timeoutMs) throws Exception {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build()
                .send(HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static boolean probeHttp(String urlStr, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
    }

    private static boolean waitForHttp(String url, int timeoutS, String name)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutS * 1_000L;
        System.out.printf("  Waiting for %-15s at %s …%n", name, url);
        while (System.currentTimeMillis() < deadline) {
            if (probeHttp(url, 2_000)) {
                System.out.printf("  %-15s ✓ ready%n", name + ":");
                return true;
            }
            System.out.print(".");
            Thread.sleep(2_000);
        }
        System.out.println();
        System.out.printf("  %-15s ✗ not ready within %ds%n", name + ":", timeoutS);
        return false;
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version")
                    .redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static String dockerContainerStatus(String name) {
        try {
            Process p = new ProcessBuilder("docker", "inspect", "-f",
                    "{{.State.Running}}", name)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (p.exitValue() != 0) return "notfound";
            return "true".equalsIgnoreCase(out) ? "running" : "exited";
        } catch (Exception e) { return "notfound"; }
    }

    private static void runDocker(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        if (p.exitValue() != 0)
            logger.warn("[OpenMedReport] Docker failed ({}): {}\n{}", p.exitValue(),
                    String.join(" ", cmd), out.trim());
    }

    private static void runDockerQuiet(String... cmd) {
        try { runDocker(cmd); } catch (Exception ignored) {}
    }

    private static boolean warmUpOpenMed() {
        String body = "{\"columns\":[{\"col_key\":\"diagnosis\",\"values\":[\"stroke\"]}]}";
        long deadline = System.currentTimeMillis() + OPENMED_WARMUP_TIMEOUT_S * 1_000L;
        int attempt = 0;
        while (System.currentTimeMillis() < deadline) {
            attempt++;
            try {
                HttpResponse<String> resp = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                        .send(HttpRequest.newBuilder()
                                        .uri(URI.create("http://localhost:8002/infer_batch"))
                                        .timeout(Duration.ofSeconds(30))
                                        .header("Content-Type", "application/json")
                                        .POST(HttpRequest.BodyPublishers.ofString(body))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null
                        && resp.body().contains("col_key")) {
                    logger.info("[OpenMedReport] Warm-up succeeded on attempt {}", attempt);
                    return true;
                }
            } catch (Exception e) {
                logger.debug("[OpenMedReport] Warm-up attempt {}: {}", attempt, e.getMessage());
            }
            System.out.print(".");
            try { Thread.sleep(3_000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
        System.out.println();
        return false;
    }

    private record TermResult(
            String colKey,
            String colSearchTerm,
            String terminology,
            Map<String, String> valueTerminology) {}
}
