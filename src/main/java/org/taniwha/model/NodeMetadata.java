package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NodeMetadata {

    @JsonProperty("@context")
    private String context; // e.g. "https://www.w3.org/ns/dcat.jsonld"

    // A list of datasets
    private List<Dataset> dataset;

    public NodeMetadata() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Dataset {
        private String title;
        private String description;
        private String identifier;

        // temporal fields
        private String issued;
        private String modified;
        private String accrualPeriodicity;

        // classification fields
        private List<String> keyword;
        private List<String> theme;
        private List<String> language;

        // references to orgs or strings
        private String publisher;
        private String contactPoint;

        // coverage
        private String spatial;
        private String temporal;

        // distributions
        private List<Distribution> distribution;

        // GETTERS / SETTERS
        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }

        public String getIssued() {
            return issued;
        }

        public void setIssued(String issued) {
            this.issued = issued;
        }

        public String getModified() {
            return modified;
        }

        public void setModified(String modified) {
            this.modified = modified;
        }

        public String getAccrualPeriodicity() {
            return accrualPeriodicity;
        }

        public void setAccrualPeriodicity(String accrualPeriodicity) {
            this.accrualPeriodicity = accrualPeriodicity;
        }

        public List<String> getKeyword() {
            return keyword;
        }

        public void setKeyword(List<String> keyword) {
            this.keyword = keyword;
        }

        public List<String> getTheme() {
            return theme;
        }

        public void setTheme(List<String> theme) {
            this.theme = theme;
        }

        public List<String> getLanguage() {
            return language;
        }

        public void setLanguage(List<String> language) {
            this.language = language;
        }

        public String getPublisher() {
            return publisher;
        }

        public void setPublisher(String publisher) {
            this.publisher = publisher;
        }

        public String getContactPoint() {
            return contactPoint;
        }

        public void setContactPoint(String contactPoint) {
            this.contactPoint = contactPoint;
        }

        public String getSpatial() {
            return spatial;
        }

        public void setSpatial(String spatial) {
            this.spatial = spatial;
        }

        public String getTemporal() {
            return temporal;
        }

        public void setTemporal(String temporal) {
            this.temporal = temporal;
        }

        public List<Distribution> getDistribution() {
            return distribution;
        }

        public void setDistribution(List<Distribution> distribution) {
            this.distribution = distribution;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Distribution {
        private String title;
        private String description;
        private String format;
        private String license;
        private String downloadURL;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getLicense() {
            return license;
        }

        public void setLicense(String license) {
            this.license = license;
        }

        public String getDownloadURL() {
            return downloadURL;
        }

        public void setDownloadURL(String downloadURL) {
            this.downloadURL = downloadURL;
        }
    }
}
