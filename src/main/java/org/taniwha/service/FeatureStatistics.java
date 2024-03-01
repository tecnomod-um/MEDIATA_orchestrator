package org.taniwha.service;

import lombok.Getter;

@Getter
public abstract class FeatureStatistics {
    final String featureName;
    final long count;
    final double percentMissing;
    final long missingValuesCount;

    public FeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount) {
        this.featureName = featureName;
        this.count = count;
        this.percentMissing = percentMissing;
        this.missingValuesCount = missingValuesCount;
    }
}
