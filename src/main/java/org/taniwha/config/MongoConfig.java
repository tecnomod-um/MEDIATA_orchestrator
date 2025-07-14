package org.taniwha.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

//MongoDB instance configurations
@Configuration
public class MongoConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoConfig.class);

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    private MongoClient mongoClient;

    @Bean
    public MongoClient mongoClient() {
        this.mongoClient = MongoClients.create(mongoUri);
        return this.mongoClient;
    }

    @PreDestroy
    public void cleanUp() {
        logger.info("Closing mongoDB connection");
        if (mongoClient != null)
            mongoClient.close();
    }
}
