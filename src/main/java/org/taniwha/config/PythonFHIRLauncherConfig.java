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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

import static org.taniwha.util.PythonLauncherUtil.*;

@Profile("!docker")
@Configuration
@ConditionalOnProperty(name = "fhir.launcher.enabled", havingValue = "true", matchIfMissing = true)
public class PythonFHIRLauncherConfig {
    private static final Logger logger = LoggerFactory.getLogger(PythonFHIRLauncherConfig.class);

    @Value("${fhir.path}")
    String projectRoot;
    @Value("${fhir.dependencies}")
    String dependencies;
    @Value("${fhir.port}")
    String port;

    @Bean
    CommandLineRunner launchPythonFHIRService() {
        return args -> {
            Path proj = Paths.get(projectRoot).toAbsolutePath();
            File dir = proj.toFile();
            if (!dir.isDirectory()) {
                logger.error("Project directory {} not found; aborting.", proj);
                return;
            }
            logger.info("FHIR project root: {}", proj);

            Map<String, String> env = new HashMap<>(System.getenv());
            loadDotEnv(proj.resolve(".env").toFile(), env);

            File venv = ensureVirtualEnv(proj, env);
            File python = pickPython(venv);
            logger.info("Using Python: {}", python);

            ensureDependencies(dir, env, python,
                    parseDeps(dependencies));

            if (!runBashQuiet(dir, env, "source .venv/bin/activate && python -c \"import en_core_web_sm\""))
                runBash(dir, env, "source .venv/bin/activate && python -m spacy download en_core_web_sm");

            String certFile = captureOutput(python, "-c", "import certifi; print(certifi.where())").trim();
            runBash(dir, env, "source .venv/bin/activate && export SSL_CERT_FILE=" + certFile +
                    " && python -m nltk.downloader stopwords punkt");

            String cmd = String.format("source .venv/bin/activate && uvicorn api.main_api_clustering:app --host 0.0.0.0 --port %s --reload", port);
            launchAsync(dir, env, cmd);
        };
    }

    private String captureOutput(File python, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = python.getAbsolutePath();
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd)
                .directory(new File(projectRoot))
                .redirectErrorStream(true)
                .start();
        try (Scanner s = new Scanner(p.getInputStream(), StandardCharsets.UTF_8).useDelimiter("\\A")) {
            p.waitFor();
            return s.hasNext() ? s.next() : "";
        }
    }
}
