package org.taniwha.service;

import java.util.Map;

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

    public String getFeatureName() {
        return featureName;
    }

    public long getCount() {
        return count;
    }

    public double getPercentMissing() {
        return percentMissing;
    }

    public long getMissingValuesCount() {
        return missingValuesCount;
    }

    public abstract Map<String, Object> getTypeStatistics();
}
