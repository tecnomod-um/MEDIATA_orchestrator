package org.taniwha.service;

import lombok.Getter;
import java.util.List;
import java.util.Map;

@Getter
public class DateFeatureStatistics extends FeatureStatistics {
    private final String earliestDate;
    private final String latestDate;
    private final Map<String, Long> dateHistogram;
    private final List<String> outliers;
    private final String mean;
    private final double stdDev;
    private final String median;
    private final String q1;
    private final String q3;

    public DateFeatureStatistics(String featureName, long count, double percentMissing, long missingValuesCount, String earliestDate, String latestDate, Map<String, Long> dateHistogram, List<String> outliers, String mean, double stdDev, String median, String q1, String q3) {
        super(featureName, count, percentMissing, missingValuesCount);
        this.earliestDate = earliestDate;
        this.latestDate = latestDate;
        this.dateHistogram = dateHistogram;
        this.outliers = outliers;
        this.mean = mean;
        this.stdDev = stdDev;
        this.median = median;
        this.q1 = q1;
        this.q3 = q3;
    }
}
