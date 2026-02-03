package org.taniwha.config;

import jakarta.annotation.PreDestroy;
import org.apache.kerby.kerberos.kdc.identitybackend.JsonIdentityBackend;
import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.identity.backend.BackendConfig;
import org.apache.kerby.kerberos.kerb.server.KdcConfig;
import org.apache.kerby.kerberos.kerb.server.KdcConfigKey;
import org.apache.kerby.kerberos.kerb.server.KdcSetting;
import org.apache.kerby.kerberos.kerb.server.impl.DefaultInternalKdcServerImpl;
import org.apache.kerby.kerberos.kerb.type.base.EncryptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.taniwha.kerberos.CustomKdcServer;
import org.taniwha.kerberos.KerberosConfigFileGenerator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

@Configuration
public class KdcServerConfig {

    private static final Logger logger = LoggerFactory.getLogger(KdcServerConfig.class);

    private CustomKdcServer kdcServer;

    @Value("${kerberos.realm}")
    private String realm;

    @Value("${kerberos.kdc.port}")
    private int kdcPort;

    @Value("${kerberos.workdir}")
    private String workDirPath;

    @Bean
    public CustomKdcServer kdcServer() throws KrbException, IOException {

        // Force IPv4 early (Docker + Java + localhost commonly causes IPv6 first)
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("java.net.preferIPv4Addresses", "true");

        prepareWorkDir();

        KdcConfig kdcConfig = new KdcConfig();
        kdcConfig.setString(KdcConfigKey.KDC_REALM.getPropertyKey(), realm);
        kdcConfig.setString(KdcConfigKey.KDC_HOST.getPropertyKey(), "127.0.0.1");
        kdcConfig.setInt(KdcConfigKey.KDC_PORT.getPropertyKey(), kdcPort);
        kdcConfig.setString(KdcConfigKey.KDC_SERVICE_NAME.getPropertyKey(), "TaniwhaKDC");
        kdcConfig.setBoolean(KdcConfigKey.KRB_DEBUG.getPropertyKey(), true);

        List<EncryptionType> encryptionTypes = Arrays.asList(
                EncryptionType.AES256_CTS_HMAC_SHA1_96,
                EncryptionType.AES128_CTS_HMAC_SHA1_96
        );
        String encTypesString = String.join(",",
                encryptionTypes.stream().map(EncryptionType::getName).toArray(String[]::new));
        kdcConfig.setString(KdcConfigKey.ENCRYPTION_TYPES.getPropertyKey(), encTypesString);

        BackendConfig backendConfig = new BackendConfig();
        backendConfig.setString(
                JsonIdentityBackend.JSON_IDENTITY_BACKEND_DIR,
                new File(workDirPath, "krb5kdc").getAbsolutePath()
        );
        backendConfig.setString(
                KdcConfigKey.KDC_IDENTITY_BACKEND,
                JsonIdentityBackend.class.getCanonicalName()
        );

        kdcServer = new CustomKdcServer(kdcConfig, backendConfig);
        kdcServer.setKdcRealm(realm);
        kdcServer.setKdcHost("127.0.0.1");
        kdcServer.setKdcPort(kdcPort);
        kdcServer.setWorkDir(new File(workDirPath));

        KdcSetting kdcSetting = new KdcSetting(kdcConfig, backendConfig);
        kdcServer.setInnerKdcImpl(new DefaultInternalKdcServerImpl(kdcSetting));

        kdcServer.init();
        
        // Retry logic to handle transient port conflicts in test environments
        int maxRetries = 3;
        int retryDelay = 200; // milliseconds
        KrbException lastException = null;
        boolean started = false;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                kdcServer.start();
                logger.info("KDC server started successfully on port: {} (attempt {})", kdcPort, attempt);
                started = true;
                break; // Success, exit retry loop
            } catch (KrbException e) {
                lastException = e;
                // Check if this is a bind exception (port already in use)
                Throwable cause = e.getCause();
                boolean isBindException = false;
                while (cause != null) {
                    if (cause instanceof java.net.BindException) {
                        isBindException = true;
                        break;
                    }
                    cause = cause.getCause();
                }
                
                if (isBindException && attempt < maxRetries) {
                    logger.warn("KDC server failed to bind to port {} on attempt {}, retrying after {}ms...", 
                            kdcPort, attempt, retryDelay);
                    try {
                        Thread.sleep(retryDelay);
                        // Exponential backoff
                        retryDelay *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new KrbException("Interrupted while retrying KDC server start", ie);
                    }
                } else {
                    // Not a bind exception or last attempt - rethrow
                    throw e;
                }
            }
        }
        
        // If we exhausted all retries without success, throw the last exception
        if (!started && lastException != null) {
            throw lastException;
        }

        createKrb5Conf();
        logger.info("KDC server initialized with realm: {} on port: {}",
                kdcServer.getKdcSetting().getKdcRealm(),
                kdcServer.getKdcSetting().getKdcPort());

        return kdcServer;
    }

    private void prepareWorkDir() throws IOException {
        File workDir = new File(workDirPath);
        if (!workDir.exists()) {
            logger.debug("Creating work directory for KDC: {}", workDir.getAbsolutePath());
            if (!workDir.mkdirs()) {
                logger.error("Failed to create work directory: {}", workDir.getAbsolutePath());
            }
        } else {
            logger.debug("Work directory already exists: {}", workDir.getAbsolutePath());
        }

        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            if (!workDir.setWritable(true, false) ||
                    !workDir.setReadable(true, false) ||
                    !workDir.setExecutable(true, false)) {
                logger.error("Failed to set permissions on work directory: {}", workDir.getAbsolutePath());
                throw new IOException("Failed to set permissions on work directory");
            }
        } else {
            logger.warn("Skipping permission adjustments as the application is running on Windows");
        }

        File krb5kdcDir = new File(workDir, "krb5kdc");
        if (!krb5kdcDir.exists()) {
            logger.debug("Creating krb5kdc directory: {}", krb5kdcDir.getAbsolutePath());
            if (!krb5kdcDir.mkdirs()) {
                logger.error("Failed to create krb5kdc directory: {}", krb5kdcDir.getAbsolutePath());
                throw new IOException("Failed to create krb5kdc directory");
            }
        }
    }

    private void createKrb5Conf() throws IOException {
        File krb5ConfDir = new File(workDirPath);
        if (!krb5ConfDir.exists() && !krb5ConfDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + krb5ConfDir.getAbsolutePath());
        }

        // IMPORTANT: generate krb5.conf pointing KDC to 127.0.0.1 (not "localhost")
        String krb5ConfContent = KerberosConfigFileGenerator.generateKrb5ConfContent(realm, kdcPort, "127.0.0.1");

        File krb5ConfFile = Paths.get(workDirPath, "krb5.conf").toFile();
        if (krb5ConfFile.getParentFile() != null &&
                !krb5ConfFile.getParentFile().exists() &&
                !krb5ConfFile.getParentFile().mkdirs()) {
            throw new IOException("Failed to create directory: " + krb5ConfFile.getParentFile().getAbsolutePath());
        }

        try (FileWriter writer = new FileWriter(krb5ConfFile)) {
            writer.write(krb5ConfContent);
        }

        logger.debug("krb5.conf file created at: {}", krb5ConfFile.getAbsolutePath());
        System.setProperty("java.security.krb5.conf", krb5ConfFile.getAbsolutePath());
    }

    @PreDestroy
    public void cleanUp() {
        logger.info("Cleaning up Kerberos resources");
        if (kdcServer != null) {
            try {
                kdcServer.stop();
            } catch (KrbException e) {
                logger.error("Failed to stop KDC server", e);
            }
        }
    }
}
