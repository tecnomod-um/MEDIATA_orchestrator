package org.taniwha.service;

import lombok.Getter;

import java.util.Map;

@Getter
public class CategoricalFeatureStatistics extends FeatureStatistics {
    private final int cardinality;
    private final String mode;
    private final int modeFrequency;
    private final double modeFrequencyPercentage;
    private final String secondMode;
    private final Integer secondModeFrequency;
    private final Double secondModePercentage;
    private final Map<String, Integer> categoryCounts;

    public CategoricalFeatureStatistics(
            String featureName, long count, double percentMissing, long missingValuesCount,
            int cardinality, String mode, int modeFrequency, double modeFrequencyPercentage,
            String secondMode, Integer secondModeFrequency, Double secondModePercentage,
            Map<String, Integer> categoryCounts) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.cardinality = cardinality;
        this.mode = mode;
        this.modeFrequency = modeFrequency;
        this.modeFrequencyPercentage = modeFrequencyPercentage;
        this.secondMode = secondMode;
        this.secondModeFrequency = secondModeFrequency;
        this.secondModePercentage = secondModePercentage;
        this.categoryCounts = categoryCounts;
    }
}
