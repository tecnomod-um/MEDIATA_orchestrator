package org.taniwha.dto;

public class AnalyticsResponseDTO {
    private String message;

    // Constructor, getters, and setters
    public AnalyticsResponseDTO() {}

    public AnalyticsResponseDTO(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
