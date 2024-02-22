package org.taniwha.service;

import java.util.List;
import java.util.Map;

// Object containing statistics extracted from a csv
public class FeatureStatistics {
    private String featureName;
    private long count;
    private double percentMissing;
    private int cardinality;
    private Map<String, Object> statistics;
    private long missingValuesCount;
    private List<Double> outliers;

    public FeatureStatistics(Map<String, Object> statistics, int cardinality, double percentMissing, long count, long missingValuesCount, String featureName, List<Double> outliers) {
        this.statistics = statistics;
        this.cardinality = cardinality;
        this.percentMissing = percentMissing;
        this.count = count;
        this.missingValuesCount = missingValuesCount;
        this.featureName = featureName;
        this.outliers = outliers;
    }

    public String getFeatureName() {
        return featureName;
    }

    public void setFeatureName(String featureName) {
        this.featureName = featureName;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public double getPercentMissing() {
        return percentMissing;
    }

    public void setPercentMissing(double percentMissing) {
        this.percentMissing = percentMissing;
    }

    public int getCardinality() {
        return cardinality;
    }

    public void setCardinality(int cardinality) {
        this.cardinality = cardinality;
    }

    public Map<String, Object> getStatistics() {
        return statistics;
    }

    public void setStatistics(Map<String, Object> statistics) {
        this.statistics = statistics;
    }

    public long getMissingValuesCount() {
        return missingValuesCount;
    }

    public void setMissingValuesCount(long missingValuesCount) {
        this.missingValuesCount = missingValuesCount;
    }

    public List<Double> getOutliers() {
        return outliers;
    }

    public void setOutliers(List<Double> outliers) {
        this.outliers = outliers;
    }
}
