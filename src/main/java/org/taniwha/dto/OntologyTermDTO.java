package org.taniwha.dto;

public class OntologyTermDTO {
    private String id;
    private String label;
    private String description;
    private String iri;

    public OntologyTermDTO() {
    }

    public OntologyTermDTO(String id, String label, String description, String iri) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.iri = iri;
    }

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIri() {
        return iri;
    }

    public void setIri(String iri) {
        this.iri = iri;
    }
}
