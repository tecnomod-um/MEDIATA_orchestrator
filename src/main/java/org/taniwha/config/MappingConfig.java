package org.taniwha.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// Checks the configured set-up params for the mapping service
@Configuration
@EnableConfigurationProperties(MappingServiceSettings.class)
public class MappingConfig {

}