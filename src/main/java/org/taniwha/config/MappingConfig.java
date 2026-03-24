package org.taniwha.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MappingConfig.MappingServiceSettings.class)
public class MappingConfig {

    @ConfigurationProperties(prefix = "mapping.service")
    public record MappingServiceSettings(
            int maxColumnDescTasks,
            int maxEnum,
            double schemaToColumnThreshold,
            double columnClusterThreshold,
            int maxSuggestionsPerSchemaField,
            int maxSchemalessTargets,
            int maxColumnsPerMapping,
            double noiseDocumentFrequencyFraction,
            int noiseDocumentFrequencyMinCount,
            int suffixNoiseMinCount,
            int descriptionConcurrencyWindow,
            int descriptionBatchColumns
    ) {
        public MappingServiceSettings {
            if (maxColumnDescTasks < 0) throw new IllegalArgumentException("maxColumnDescTasks must be >= 0");
            if (maxEnum < 0) throw new IllegalArgumentException("maxEnum must be >= 0");
            if (maxSuggestionsPerSchemaField < 0) throw new IllegalArgumentException("maxSuggestionsPerSchemaField must be >= 0");
            if (maxSchemalessTargets < 0) throw new IllegalArgumentException("maxSchemalessTargets must be >= 0");
            if (maxColumnsPerMapping < 1) throw new IllegalArgumentException("maxColumnsPerMapping must be >= 1");
            if (noiseDocumentFrequencyMinCount < 0) throw new IllegalArgumentException("noiseDocumentFrequencyMinCount must be >= 0");
            if (suffixNoiseMinCount < 0) throw new IllegalArgumentException("suffixNoiseMinCount must be >= 0");
            if (descriptionConcurrencyWindow < 1) throw new IllegalArgumentException("descriptionConcurrencyWindow must be >= 1");
            if (descriptionBatchColumns < 1) throw new IllegalArgumentException("descriptionBatchColumns must be >= 1");
        }
    }
}