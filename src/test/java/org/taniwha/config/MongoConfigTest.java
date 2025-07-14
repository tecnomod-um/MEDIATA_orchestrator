package org.taniwha.config;

import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
class MongoConfigTest {

    @Test
    @SuppressWarnings("unchecked")
    void mongoClientBean_usesProvidedUri() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            String uri = "mongodb://localhost:37017";
            ctx.getEnvironment().getSystemProperties().put("spring.data.mongodb.uri", uri);
            ctx.register(MongoConfig.class);
            ctx.refresh();

            MongoClient client = ctx.getBean(MongoClient.class);
            assertThat(client).as("MongoClient bean").isNotNull();

            Method mGetSettings = client.getClass().getMethod("getSettings");
            Object settings = mGetSettings.invoke(client);
            Method mClusterSettings = settings.getClass().getMethod("getClusterSettings");
            Object clusterSettings = mClusterSettings.invoke(settings);
            Method mHosts = clusterSettings.getClass().getMethod("getHosts");
            List<?> hosts = (List<?>) mHosts.invoke(clusterSettings);

            assertThat(hosts).isNotEmpty();
            Object firstHost = hosts.get(0);
            Method mHostName = firstHost.getClass().getMethod("getHost");
            Method mPort = firstHost.getClass().getMethod("getPort");
            String hostName = (String) mHostName.invoke(firstHost);
            int port = (Integer) mPort.invoke(firstHost);
            assertThat(hostName).isEqualTo("localhost");
            assertThat(port).isEqualTo(37017);
        }
    }
}
