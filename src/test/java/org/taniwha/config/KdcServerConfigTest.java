package org.taniwha.config;

import org.apache.kerby.kerberos.kdc.identitybackend.JsonIdentityBackend;
import org.apache.kerby.kerberos.kerb.identity.backend.BackendConfig;
import org.apache.kerby.kerberos.kerb.server.KdcConfigKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.taniwha.kerberos.MongoIdentityBackend;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KdcServerConfigTest {

    @TempDir
    Path tempDir;

    private KdcServerConfig newConfig() {
        KdcServerConfig cfg = new KdcServerConfig();
        // Inject values that @Value would normally provide
        ReflectionTestUtils.setField(cfg, "realm", "TEST.REALM");
        ReflectionTestUtils.setField(cfg, "kdcPort", 10088);
        ReflectionTestUtils.setField(cfg, "workDirPath", tempDir.toString());
        ReflectionTestUtils.setField(cfg, "identityStore", "json");
        ReflectionTestUtils.setField(cfg, "mongoUri", "mongodb://mongodb:27017/mediata");
        ReflectionTestUtils.setField(cfg, "mongoCollection", "kerberos_identities");
        ReflectionTestUtils.setField(cfg, "migrateJson", true);
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

    @Test
    void jsonBackendRemainsDefaultOutsideDocker() {
        KdcServerConfig cfg = newConfig();

        BackendConfig backend = ReflectionTestUtils.invokeMethod(
                cfg, "createBackendConfig", new MockEnvironment());

        assertThat(backend.getString(KdcConfigKey.KDC_IDENTITY_BACKEND.getPropertyKey()))
                .isEqualTo(JsonIdentityBackend.class.getCanonicalName());
    }

    @Test
    void dockerProfileCanSelectMongoBackend() {
        KdcServerConfig cfg = newConfig();
        ReflectionTestUtils.setField(cfg, "identityStore", "mongodb");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("docker");

        BackendConfig backend = ReflectionTestUtils.invokeMethod(
                cfg, "createBackendConfig", environment);

        assertThat(backend.getString(KdcConfigKey.KDC_IDENTITY_BACKEND.getPropertyKey()))
                .isEqualTo(MongoIdentityBackend.class.getCanonicalName());
        assertThat(backend.getString(MongoIdentityBackend.MONGODB_URI))
                .isEqualTo("mongodb://mongodb:27017/mediata");
    }

    @Test
    void mongoBackendIsRejectedOutsideDocker() {
        KdcServerConfig cfg = newConfig();
        ReflectionTestUtils.setField(cfg, "identityStore", "mongodb");

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(
                        cfg, "createBackendConfig", new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "The MongoDB Kerberos identity store is supported only by Docker deployments");
    }
}
