package org.taniwha.dto;

import org.taniwha.service.DateFeatureStatistics;
import org.taniwha.service.FeatureStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AnalyticsResponseDTO {
    private String message;
    private List<FeatureStatistics> continuousFeatures;
    private List<FeatureStatistics> categoricalFeatures;
    private Map<String, DateFeatureStatistics> dateStatistics;

    public AnalyticsResponseDTO() {
        this.continuousFeatures = new ArrayList<>();
        this.categoricalFeatures = new ArrayList<>();
    }

    // In case no data was generated, an empty error msg wil be sent
    public AnalyticsResponseDTO(String message) {
        this();
        this.message = message;
    }

    public Map<String, DateFeatureStatistics> getDateStatistics() {
        return dateStatistics;
    }

    public void setDateStatistics(Map<String, DateFeatureStatistics> dateStatistics) {
        this.dateStatistics = dateStatistics;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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
