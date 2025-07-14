package org.taniwha.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PythonLauncherUtilTest {

    @Test
    void parseDeps_trimsAndFilters() {
        List<String> deps = PythonLauncherUtil.parseDeps(" pkg1, pkg2 ,,pkg3 , ");
        assertThat(deps).containsExactly("pkg1", "pkg2", "pkg3");
    }

    @Test
    void loadDotEnv_readsOnlyKeyValueLines(@TempDir Path tmp) throws IOException {
        Path envFile = tmp.resolve(".env");
        try (BufferedWriter w = Files.newBufferedWriter(envFile)) {
            w.write("# comment\n");
            w.write("  A=1  \n");
            w.write("B =  two words\n");
            w.write("\n");
            w.write("INVALID\n");
            w.write("C=3=extra\n");
        }
        Map<String, String> env = new HashMap<>();
        PythonLauncherUtil.loadDotEnv(envFile.toFile(), env);
        assertThat(env)
                .hasSize(3)
                .containsEntry("A", "1")
                .containsEntry("B", "two words")
                .containsEntry("C", "3=extra");
    }

    @Test
    void loadDotEnv_noFileDoesNothing(@TempDir Path tmp) {
        File missing = tmp.resolve("nope.env").toFile();
        Map<String, String> env = new HashMap<>();
        PythonLauncherUtil.loadDotEnv(missing, env);
        assertThat(env).isEmpty();
    }

    @Test
    void pickPython_prefersPython3OverPython(@TempDir Path tmp) throws IOException {
        Path venv = tmp.resolve(".venv/bin");
        Files.createDirectories(venv);
        Files.createFile(venv.resolve("python"));
        assertThat(PythonLauncherUtil.pickPython(tmp.resolve(".venv").toFile()).getName())
                .isEqualTo("python");
        Files.createFile(venv.resolve("python3"));
        assertThat(PythonLauncherUtil.pickPython(tmp.resolve(".venv").toFile()).getName())
                .isEqualTo("python3");
    }

    @Test
    void runBash_and_runBashQuiet_successAndFailure(@TempDir Path tmp) {
        Map<String, String> env = Collections.emptyMap();
        assertThat(PythonLauncherUtil.runBash(tmp.toFile(), env, "true")).isTrue();
        assertThat(PythonLauncherUtil.runBashQuiet(tmp.toFile(), env, "true")).isTrue();
        assertThat(PythonLauncherUtil.runBash(tmp.toFile(), env, "false")).isFalse();
        assertThat(PythonLauncherUtil.runBashQuiet(tmp.toFile(), env, "false")).isFalse();
    }

    @Test
    void ensureVirtualEnv_createsVenvDir(@TempDir Path tmp) throws IOException {
        Path proj = tmp.resolve("project");
        Files.createDirectories(proj);
        File venv = PythonLauncherUtil.ensureVirtualEnv(proj, new HashMap<>());
        assertThat(venv).exists().isDirectory();
    }

    @Test
    void ensureDependencies_doesNotThrowEvenIfPipCommandsFail(@TempDir Path tmp) throws IOException {
        Path proj = tmp.resolve("p");
        Files.createDirectories(proj);
        Path bin = proj.resolve(".venv/bin");
        Files.createDirectories(bin);
        Files.createFile(bin.resolve("python3"));

        File pythonExe = bin.resolve("python3").toFile();
        Map<String, String> env = new HashMap<>();
        PythonLauncherUtil.ensureDependencies(proj.toFile(), env, pythonExe, Arrays.asList("dep1", ""));
    }

    @Test
    void launchAsync_doesNotThrowForHarmlessCommand(@TempDir Path tmp) {
        File cwd = tmp.toFile();
        Map<String, String> env = new HashMap<>();
        PythonLauncherUtil.launchAsync(cwd, env, "true");
    }
}
