package org.taniwha.service;

import java.util.Map;

public class CategoricalFeatureStatistics extends FeatureStatistics {
    private final int cardinality;
    private final String mode;
    private final int modeFrequency;
    private final double modeFrequencyPercentage;
    private final String secondMode;
    private final Integer secondModeFrequency;
    private final Double secondModePercentage;
    private final Map<String, Object> statistics;

    public CategoricalFeatureStatistics(
            String featureName, long count, double percentMissing, long missingValuesCount,
            int cardinality, String mode, int modeFrequency, double modeFrequencyPercentage,
            String secondMode, Integer secondModeFrequency, Double secondModePercentage,
            Map<String, Object> statistics) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.cardinality = cardinality;
        this.mode = mode;
        this.modeFrequency = modeFrequency;
        this.modeFrequencyPercentage = modeFrequencyPercentage;
        this.secondMode = secondMode;
        this.secondModeFrequency = secondModeFrequency;
        this.secondModePercentage = secondModePercentage;
        this.statistics = statistics;
    }

    @Override
    public Map<String, Object> getTypeStatistics() {
        return statistics;
    }

    public int getCardinality() {
        return cardinality;
    }

    public String getMode() {
        return mode;
    }

    public int getModeFrequency() {
        return modeFrequency;
    }

    public double getModeFrequencyPercentage() {
        return modeFrequencyPercentage;
    }

    public String getSecondMode() {
        return secondMode;
    }

    public Integer getSecondModeFrequency() {
        return secondModeFrequency;
    }

    public Double getSecondModePercentage() {
        return secondModePercentage;
    }

    public Map<String, Object> getStatistics() {
        return statistics;
    }
}
