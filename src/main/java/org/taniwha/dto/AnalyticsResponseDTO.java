package org.taniwha.dto;

import org.taniwha.service.DateStatistics;
import org.taniwha.service.FeatureStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AnalyticsResponseDTO {
    private String message;
    private List<FeatureStatistics> continuousFeatures;
    private List<FeatureStatistics> categoricalFeatures;
    private Map<String, DateStatistics> dateStatistics;
    private Map<String, List<Double>> histograms;

    public AnalyticsResponseDTO() {
        this.continuousFeatures = new ArrayList<>();
        this.categoricalFeatures = new ArrayList<>();
        this.histograms = new HashMap<>();
    }

    // In case no data was generated, an empty error msg wil be sent
    public AnalyticsResponseDTO(String message) {
        this();
        this.message = message;
    }

    public Map<String, DateStatistics> getDateStatistics() {
        return dateStatistics;
    }

    public void setDateStatistics(Map<String, DateStatistics> dateStatistics) {
        this.dateStatistics = dateStatistics;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, List<Double>> getHistograms() {
        return histograms;
    }

    public void setHistograms(Map<String, List<Double>> histograms) {
        this.histograms = histograms;
    }

    public List<FeatureStatistics> getCategoricalFeatures() {
        return categoricalFeatures;
    }

    public void setCategoricalFeatures(List<FeatureStatistics> categoricalFeatures) {
        this.categoricalFeatures = categoricalFeatures;
    }

    public List<FeatureStatistics> getContinuousFeatures() {
        return continuousFeatures;
    }

    public void setContinuousFeatures(List<FeatureStatistics> continuousFeatures) {
        this.continuousFeatures = continuousFeatures;
    }
}
