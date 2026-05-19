package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.taniwha.util.PythonLauncherUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(SpringExtension.class)
class PythonLauncherConfigTest {

    @TempDir
    Path tmp;

    @Test
    void launchPythonService_happyPath_invokesHelpers() throws Exception {
        Path scriptDir = Files.createDirectories(tmp.resolve("rdfbuilder"));
        Path scriptFile = Files.createFile(scriptDir.resolve("server.py"));
        Path venvDir = Files.createDirectories(tmp.resolve("venv"));
        Path pyExe = Files.createFile(venvDir.resolve("python"));

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getSystemProperties().put("rdfbuilder.path", scriptFile.toString());
            ctx.getEnvironment().getSystemProperties().put("rdfbuilder.venv", venvDir.toString());
            ctx.getEnvironment().getSystemProperties().put("rdfbuilder.dependencies", "flask,requests");
            ctx.getEnvironment().getSystemProperties().put("rdfbuilder.port", "8123");
            ctx.register(PythonLauncherConfig.class);
            ctx.refresh();

            CommandLineRunner runner = ctx.getBean(CommandLineRunner.class);
            assertThat(runner).isNotNull();

            try (MockedStatic<PythonLauncherUtil> util =
                         Mockito.mockStatic(PythonLauncherUtil.class)) {
                util.when(() -> PythonLauncherUtil.parseDeps("flask,requests"))
                        .thenReturn(Arrays.asList("flask", "requests"));
                util.when(() -> PythonLauncherUtil.pickPython(venvDir.toFile()))
                        .thenReturn(pyExe.toFile());
                util.when(() -> PythonLauncherUtil.ensureVirtualEnv(
                                any(Path.class), anyMap()))
                        .thenAnswer(i -> null);
                util.when(() -> PythonLauncherUtil.ensureDependencies(
                                any(File.class), anyMap(), any(File.class), anyList()))
                        .thenAnswer(i -> null);
                util.when(() -> PythonLauncherUtil.launchAsync(
                                any(File.class), anyMap(), anyString()))
                        .thenAnswer(i -> null);
                runner.run();
                util.verify(() -> PythonLauncherUtil.pickPython(venvDir.toFile()));
                util.verify(() -> PythonLauncherUtil.ensureDependencies(
                        eq(scriptDir.toFile()), anyMap(), eq(pyExe.toFile()), eq(Arrays.asList("flask", "requests"))));
                ArgumentCaptor<String> cmdCap = ArgumentCaptor.forClass(String.class);
                util.verify(() -> PythonLauncherUtil.launchAsync(
                        eq(scriptDir.toFile()), any(Map.class), cmdCap.capture()));
                String cmd = cmdCap.getValue();
                assertThat(cmd).contains(pyExe.toString())
                        .contains("uvicorn")
                        .contains(":app")
                        .contains("--port 8123");
            }
        }
    }
}
