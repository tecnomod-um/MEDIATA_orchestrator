package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
class KdcClientConfigTest {

    @Test
    void krbClientBean_isCreatedWithTcpEnabledAndUdpDisabled() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getSystemProperties().put("kerberos.realm", "EXAMPLE.COM");
            ctx.getEnvironment().getSystemProperties().put("kerberos.kdc.port", "8800");
            ctx.register(KdcClientConfig.class);
            ctx.refresh();
            KrbClient client = ctx.getBean(KrbClient.class);
            assertThat(client).as("KrbClient bean").isNotNull();
        }
    }
}
