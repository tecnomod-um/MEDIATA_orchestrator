package org.taniwha.service;

import java.util.HashMap;
import java.util.Map;

// Statistics made for date columns
public class DateStatistics {
    private int count;
    private long missingValuesCount;
    private String earliestDate;
    private String latestDate;
    private Map<String, Long> dateHistogram = new HashMap<>();

    public DateStatistics(int count, long missingValuesCount, String earliestDate, String latestDate) {
        this.count = count;
        this.missingValuesCount = missingValuesCount;
        this.earliestDate = earliestDate;
        this.latestDate = latestDate;
    }

    public Map<String, Long> getDateHistogram() {
        return dateHistogram;
    }

    public void setDateHistogram(Map<String, Long> dateHistogram) {
        this.dateHistogram = dateHistogram;
    }

    public long getMissingValuesCount() {
        return missingValuesCount;
    }

    public void setMissingValuesCount(long missingValuesCount) {
        this.missingValuesCount = missingValuesCount;
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

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
