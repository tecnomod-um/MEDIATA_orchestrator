package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.nio.file.Paths;

@Configuration
@ConditionalOnProperty(name = "kerberos.enabled", havingValue = "true", matchIfMissing = true)
public class KdcClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(KdcClientConfig.class);

    @Value("${kerberos.workdir}")
    private String workDirPath;

    @Bean
    public KrbClient krbClient() throws Exception {
        File krb5Conf = Paths.get(workDirPath, "krb5.conf").toFile();
        if (!krb5Conf.exists()) {
            throw new IllegalStateException("krb5.conf not found at: " + krb5Conf.getAbsolutePath());
        }

        // Kerby supports KrbClient(File). Your compile log confirms this constructor exists.
        KrbClient client = new KrbClient(krb5Conf);
        client.init();

        logger.info("Kerberos client initialized using {}", krb5Conf.getAbsolutePath());
        return client;
    }
}
