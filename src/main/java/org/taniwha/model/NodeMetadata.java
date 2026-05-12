package org.taniwha.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeMetadata {

    @JsonProperty("@context")
    private String context;

    @JsonProperty("@type")
    private String type;

    private String sourceFile;

    private List<Dataset> dataset;

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Dataset {
        private String uri;
        private String title;
        private String description;
        private String identifier;
        private String issued;
        private String modified;
        private String accrualPeriodicity;
        private List<Object> keyword;
        private List<Object> theme;
        private List<Object> language;
        private Object publisher;
        private Object contactPoint;
        private Object spatial;
        private Object temporal;
        private List<Distribution> distribution;
        private Object accessRights;
        private List<Object> applicableLegislation;
        private List<Object> codeValues;
        private List<Object> codingSystem;
        private List<Object> conformsTo;
        private Object custodian;
        private Boolean hasPersonalData;
        private Object healthDataAccessBody;
        private List<Object> healthCategory;
        private List<Object> healthTheme;
        private Boolean isStructured;
        private Long numberOfRecords;
        private Long numberOfUniqueIndividuals;
        private Object provenance;
        private List<Object> type;
        private List<Variable> variables;
        private Object wasGeneratedBy;

        @JsonIgnore
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String key, Object value) {
            additionalProperties.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> any() {
            return additionalProperties;
        }
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Distribution {
        private String uri;
        private String title;
        private String description;
        private Object format;
        private Object license;
        private Object downloadURL;
        private Object accessURL;
        private Object mediaType;
        private Object byteSize;
        private Object availability;
        private String issued;
        private String modified;
        private Object conformsTo;
        private Object accessService;

        @JsonIgnore
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String key, Object value) {
            additionalProperties.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> any() {
            return additionalProperties;
        }
    }

    @Getter
    @Setter
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Variable {
        private String uri;
        private String name;
        private String definition;
        private Object dataType;
        private Object codingSystem;
        private Object valueDomain;

        @JsonIgnore
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String key, Object value) {
            additionalProperties.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> any() {
            return additionalProperties;
        }
    }
}
