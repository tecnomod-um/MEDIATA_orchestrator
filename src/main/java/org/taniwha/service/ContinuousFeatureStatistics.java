package org.taniwha.service;

import lombok.Getter;

import java.util.List;

@Getter
public class ContinuousFeatureStatistics extends FeatureStatistics {
    private final int cardinality;
    private final double min;
    private final double max;
    private final double mean;
    private final double stdDev;
    private final double qrt1;
    private final double median;
    private final double qrt3;
    private final List<Double> histogram;
    private final List<String> binRanges;
    private final List<Double> outliers;

    public ContinuousFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, int cardinality, double min, double max, double mean, double stdDev, double qrt1, double median, double qrt3, List<Double> histogramBins, List<String> binRanges, List<Double> outliers) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.cardinality = cardinality;
        this.min = min;
        this.max = max;
        this.mean = mean;
        this.stdDev = stdDev;
        this.qrt1 = qrt1;
        this.median = median;
        this.qrt3 = qrt3;
        this.histogram = histogramBins;
        this.binRanges = binRanges;
        this.outliers = outliers;
    }
}
