package org.taniwha.util;

import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PythonLauncherUtil {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(PythonLauncherUtil.class);

    public static File ensureVirtualEnv(Path projectDir, Map<String, String> env) {
        File dir = projectDir.toFile();
        File venv = projectDir.resolve(".venv").toFile();
        File activate = new File(venv, "bin/activate");
        if (!activate.exists()) {
            logger.info("Creating virtualenv…");
            if (!runBash(dir, env, "python3 -m venv .venv"))
                throw new IllegalStateException("Could not create virtualenv in " + projectDir);
        }
        return venv;
    }

    public static List<String> parseDeps(String commaList) {
        return Arrays.stream(commaList.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    public static void loadDotEnv(File dotEnv, Map<String, String> env) {
        if (!dotEnv.exists()) return;
        logger.info("Loading .env");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(dotEnv), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    env.put(line.substring(0, eq).trim(),
                            line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read .env: {}", e.getMessage());
        }
    }

    public static boolean runBash(File cwd, Map<String, String> env, String cmd) {
        logger.info("$ {}", cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd).directory(cwd).inheritIO();
            pb.environment().putAll(env);
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            logger.error("Error running '{}': {}", cmd, e.getMessage());
            return false;
        }
    }

    public static boolean runBashQuiet(File cwd, Map<String, String> env, String cmd) {
        return runBash(cwd, env, cmd + " >/dev/null 2>&1");
    }

    public static void ensureDependencies(File cwd, Map<String, String> env, File venvBin, List<String> deps) {
        runBash(cwd, env, "source " + venvBin.getParentFile().getAbsolutePath() + "/activate && pip install --upgrade pip");

        for (String dep : deps) {
            String check = "source " + venvBin.getParentFile().getAbsolutePath() + "/activate && pip show " + dep;
            if (!runBashQuiet(cwd, env, check)) {
                logger.info("Installing missing dependency '{}'", dep);
                runBash(cwd, env, "source " + venvBin.getParentFile().getAbsolutePath() + "/activate && pip install " + dep);
            }
        }
    }

    public static void launchAsync(File cwd, Map<String, String> env, String uvicornCmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", uvicornCmd).directory(cwd).inheritIO();
            pb.environment().putAll(env);
            Process p = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Stopping Python service…");
                p.destroy();
            }));
        } catch (IOException e) {
            logger.error("Failed to start '{}': {}", uvicornCmd, e.getMessage());
        }
    }

    public static File pickPython(File venvDir) {
        File py3 = new File(venvDir, "bin/python3");
        File py = new File(venvDir, "bin/python");
        return py3.exists() ? py3 : py;
    }
}
