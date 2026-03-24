package org.taniwha.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.taniwha.util.PythonLauncherUtil.*;

/**
 * Launches the OpenMed Terminology Service (Python / FastAPI) as a background
 * process when running outside Docker.
 *
 * <p>The service is started on {@code openmed.port} (default 8002) and is used
 * by {@link org.taniwha.service.OpenMedTerminologyService} to infer
 * SNOMED-searchable terminology terms for dataset columns and values.</p>
 *
 * <p>Disable by setting {@code openmed.launcher.enabled=false} in
 * {@code application.properties} or the {@code OPENMED_LAUNCHER_ENABLED}
 * environment variable.</p>
 */
@Profile("!docker")
@Configuration
@ConditionalOnProperty(name = "openmed.launcher.enabled", havingValue = "true", matchIfMissing = true)
public class OpenMedLauncherConfig {

    private static final Logger logger = LoggerFactory.getLogger(OpenMedLauncherConfig.class);

    @Value("${openmed.path:mediata-openmed}")
    String projectRoot;

    @Value("${openmed.dependencies:fastapi, uvicorn[standard], transformers, torch}")
    String dependencies;

    @Value("${openmed.port:8002}")
    String port;

    @Bean
    CommandLineRunner launchOpenMedService() {
        return args -> {
            Path proj = Paths.get(projectRoot).toAbsolutePath();
            File dir = proj.toFile();

            if (!dir.isDirectory()) {
                logger.error("[OpenMed] Project directory '{}' not found; skipping launch.", proj);
                return;
            }

            logger.info("[OpenMed] Project root: {}", proj);

            Map<String, String> env = new HashMap<>(System.getenv());
            loadDotEnv(proj.resolve(".env").toFile(), env);

            File venv = ensureVirtualEnv(proj, env);
            File python = pickPython(venv);
            logger.info("[OpenMed] Using Python: {}", python);

            ensureDependencies(dir, env, python, parseDeps(dependencies));

            String cmd = String.format(
                    "source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port %s --reload",
                    port
            );
            launchAsync(dir, env, cmd);
            logger.info("[OpenMed] Launched OpenMed service on port {}", port);
        };
    }
}
