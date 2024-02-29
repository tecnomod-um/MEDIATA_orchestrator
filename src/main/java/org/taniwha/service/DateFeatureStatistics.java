package org.taniwha.service;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Setter
@Getter
public class DateFeatureStatistics extends FeatureStatistics {
    private String earliestDate;
    private String latestDate;
    private Map<String, Long> dateHistogram;
    private List<String> outliers;
    private String mean;
    private double stdDev;
    private String median;
    private String q1;
    private String q3;

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

    @Override
    public Map<String, Object> getTypeStatistics() {
        return Map.of(
                "EarliestDate", earliestDate,
                "LatestDate", latestDate,
                "DateHistogram", dateHistogram,
                "Outliers", outliers,
                "Mean", mean,
                "StdDev", stdDev,
                "Median", median,
                "Qrt1", q1,
                "Qrt3", q3
        );
    }

}
