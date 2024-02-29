package org.taniwha.service;

import lombok.Getter;

import java.util.Map;

@Getter
public abstract class FeatureStatistics {
    protected String featureName;
    protected long count;
    protected double percentMissing;
    protected long missingValuesCount;

    public FeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount) {
        this.featureName = featureName;
        this.count = count;
        this.percentMissing = percentMissing;
        this.missingValuesCount = missingValuesCount;
    }

    public abstract Map<String, Object> getTypeStatistics();
}
