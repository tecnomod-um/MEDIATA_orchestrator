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

import static org.taniwha.util.PythonLauncherUtil.*;

@Profile("!docker")
@Configuration
@ConditionalOnProperty(name = "python.launcher.enabled", havingValue = "true", matchIfMissing = true)
public class PythonLauncherConfig {
    private static final Logger logger = LoggerFactory.getLogger(PythonLauncherConfig.class);

    @Value("${rdfbuilder.path}")
    String scriptPath;
    @Value("${rdfbuilder.venv}")
    String venvPath;
    @Value("${rdfbuilder.dependencies}")
    String dependencies;
    @Value("${rdfbuilder.port}")
    String port;

    @Bean
    CommandLineRunner launchPythonService() {
        return args -> {
            Path script = Paths.get(scriptPath).toAbsolutePath();
            File scriptFile = script.toFile();
            File scriptDir = scriptFile.getParentFile();

            File venvDir = Paths.get(venvPath).toAbsolutePath().toFile();
            File python = pickPython(venvDir);
            if (!venvDir.exists() || !python.exists()) {
                ensureVirtualEnv(venvDir.toPath(), System.getenv());
                python = pickPython(venvDir);
            }
            logger.info("Using Python executable: {}", python);

            ensureDependencies(scriptDir, System.getenv(), python,
                    parseDeps(dependencies));

            String module = scriptFile.getName().replaceFirst("\\.py$", "");
            String cmd = String.format(
                    "%s -m uvicorn %s:app --host 0.0.0.0 --port %s --reload",
                    python.getAbsolutePath(), module, port
            );
            launchAsync(scriptDir, System.getenv(), cmd);
        };
    }
}
