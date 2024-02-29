package org.taniwha.service;

import java.util.List;
import java.util.Map;

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

    public String getEarliestDate() {
        return earliestDate;
    }

    public void setEarliestDate(String earliestDate) {
        this.earliestDate = earliestDate;
    }

    public String getLatestDate() {
        return latestDate;
    }

    public void setLatestDate(String latestDate) {
        this.latestDate = latestDate;
    }

    public Map<String, Long> getDateHistogram() {
        return dateHistogram;
    }

    public void setDateHistogram(Map<String, Long> dateHistogram) {
        this.dateHistogram = dateHistogram;
    }

    public String getMean() {
        return mean;
    }

    public void setMean(String mean) {
        this.mean = mean;
    }

    public List<String> getOutliers() {
        return outliers;
    }

    public void setOutliers(List<String> outliers) {
        this.outliers = outliers;
    }

    public double getStdDev() {
        return stdDev;
    }

    public void setStdDev(double stdDev) {
        this.stdDev = stdDev;
    }

    public String getQ1() {
        return q1;
    }

    public void setQ1(String q1) {
        this.q1 = q1;
    }

    public String getMedian() {
        return median;
    }

    public void setMedian(String median) {
        this.median = median;
    }

    public String getQ3() {
        return q3;
    }

    public void setQ3(String q3) {
        this.q3 = q3;
    }
}
