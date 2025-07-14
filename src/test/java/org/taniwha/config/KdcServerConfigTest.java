package org.taniwha.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class KdcServerConfigTest {

    @TempDir
    Path tempDir;

    private KdcServerConfig newConfig() {
        KdcServerConfig cfg = new KdcServerConfig();
        // Inject values that @Value would normally provide
        ReflectionTestUtils.setField(cfg, "realm", "TEST.REALM");
        ReflectionTestUtils.setField(cfg, "kdcPort", 10088);
        ReflectionTestUtils.setField(cfg, "workDirPath", tempDir.toString());
        return cfg;
    }

    @Test
    void prepareWorkDir_createsExpectedStructure() throws Exception {
        KdcServerConfig cfg = newConfig();

        // invoke private void prepareWorkDir()
        ReflectionTestUtils.invokeMethod(cfg, "prepareWorkDir");

        assertThat(tempDir.resolve("krb5kdc")).exists().isDirectory();
    }

    @Test
    void createKrb5Conf_writesFileAndSetsSystemProperty() throws Exception {
        KdcServerConfig cfg = newConfig();

        // we need the work dir first
        ReflectionTestUtils.invokeMethod(cfg, "prepareWorkDir");
        ReflectionTestUtils.invokeMethod(cfg, "createKrb5Conf");

        Path confFile = tempDir.resolve("krb5.conf");
        assertThat(confFile).exists().isRegularFile();
        assertThat(System.getProperty("java.security.krb5.conf"))
                .isEqualTo(confFile.toAbsolutePath().toString());
    }
}
