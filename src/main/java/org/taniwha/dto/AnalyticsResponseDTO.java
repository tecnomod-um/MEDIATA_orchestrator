package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;
import org.taniwha.service.DateFeatureStatistics;
import org.taniwha.service.FeatureStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Setter
@Getter
public class AnalyticsResponseDTO {
    private String message;

    private List<FeatureStatistics> continuousFeatures;
    private List<FeatureStatistics> categoricalFeatures;
    private List<DateFeatureStatistics> dateFeatures;

    private Map<String, Map<String, Double>> covariances;
    private Map<String, Map<String, Double>> pearsonCorrelations;
    private Map<String, Map<String, Double>> spearmanCorrelations;
    private Map<String, Map<String, Double>> chiSquareTest;


    public AnalyticsResponseDTO() {
        this.continuousFeatures = new ArrayList<>();
        this.categoricalFeatures = new ArrayList<>();

        this.covariances= new HashMap<>();
        this.pearsonCorrelations = new HashMap<>();
        this.spearmanCorrelations = new HashMap<>();
        this.chiSquareTest = new HashMap<>();
    }

    // In case no data was generated, an empty error msg wil be sent
    public AnalyticsResponseDTO(String message) {
        this();
        this.message = message;
    }
}
