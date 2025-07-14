package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.taniwha.util.PythonLauncherUtil;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
class PythonFHIRLauncherConfigTest {

    @Test
    void launchPythonFHIRService_directoryMissing_exitsWithoutHeavyWork() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getSystemProperties().put("fhir.path", "this/dir/does/not/exist");
            ctx.getEnvironment().getSystemProperties().put("fhir.dependencies", "requests");
            ctx.getEnvironment().getSystemProperties().put("fhir.port", "9001");

            ctx.register(PythonFHIRLauncherConfig.class);
            ctx.refresh();

            CommandLineRunner clr = ctx.getBean(CommandLineRunner.class);
            assertThat(clr).isNotNull();

            try (MockedStatic<PythonLauncherUtil> staticMock =
                         Mockito.mockStatic(PythonLauncherUtil.class, Mockito.CALLS_REAL_METHODS)) {
                clr.run();
                staticMock.verifyNoInteractions();
            }
        }
    }
}
