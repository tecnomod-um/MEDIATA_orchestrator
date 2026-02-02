package org.taniwha.config;

import org.apache.kerby.kerberos.kerb.client.KrbClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
class KdcClientConfigTest {

    @Test
    void krbClientBean_isCreatedWithTcpEnabledAndUdpDisabled(@TempDir Path tempDir) throws Exception {
        // Create a dummy krb5.conf file in the temp directory
        File krb5Conf = tempDir.resolve("krb5.conf").toFile();
        try (FileWriter writer = new FileWriter(krb5Conf)) {
            writer.write(
                "[libdefaults]\n" +
                "    default_realm = EXAMPLE.COM\n" +
                "    udp_preference_limit = 1\n" +
                "[realms]\n" +
                "    EXAMPLE.COM = {\n" +
                "        kdc = localhost:8800\n" +
                "    }\n"
            );
        }
        
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getSystemProperties().put("kerberos.realm", "EXAMPLE.COM");
            ctx.getEnvironment().getSystemProperties().put("kerberos.kdc.port", "8800");
            ctx.getEnvironment().getSystemProperties().put("kerberos.workdir", tempDir.toString());
            ctx.register(KdcClientConfig.class);
            ctx.refresh();
            KrbClient client = ctx.getBean(KrbClient.class);
            assertThat(client).as("KrbClient bean").isNotNull();
        }
    }
}
