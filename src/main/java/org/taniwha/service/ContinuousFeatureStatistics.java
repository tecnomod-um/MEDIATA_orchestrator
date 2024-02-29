package org.taniwha.service;

import java.util.List;
import java.util.Map;

public class ContinuousFeatureStatistics extends FeatureStatistics {
    private int cardinality;
    private Map<String, Object> statistics;
    private List<Double> outliers;

    public ContinuousFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, int cardinality, Map<String, Object> statistics, List<Double> outliers) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.cardinality = cardinality;
        this.statistics = statistics;
        this.outliers = outliers;
    }

    @Override
    public Map<String, Object> getTypeStatistics() {
        return statistics;
    }

    public int getCardinality() {
        return cardinality;
    }

    public List<Double> getOutliers() {
        return outliers;
    }
}