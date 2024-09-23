package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.KrbException;
import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Kerby client configurations
@Configuration
public class KdcClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(KdcClientConfig.class);

    @Value("${kerberos.realm}")
    private String realm;

    @Value("${kerberos.kdc.port}")
    private int kdcPort;

    @Bean
    public KrbClient krbClient() throws KrbException {
        KrbClient krbClient = new KrbClient();
        krbClient.setKdcRealm(realm);
        krbClient.setKdcHost("localhost");
        krbClient.setKdcTcpPort(kdcPort);
        krbClient.setAllowUdp(false);
        krbClient.setAllowTcp(true);
        krbClient.init();

        logger.info("KDC client initialized for realm: {} on KDC host: {} and port: {}", realm, "localhost", kdcPort);
        return krbClient;
    }
}
