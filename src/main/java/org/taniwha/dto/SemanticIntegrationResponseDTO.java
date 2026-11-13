package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SemanticIntegrationResponseDTO {
    private boolean csvSaved;
    private String csvMessage;
    private boolean rdfGenerated;
    private String rdfMessage;

    public SemanticIntegrationResponseDTO(boolean csvSaved, String csvMessage,
                                boolean rdfGenerated, String rdfMessage) {
        this.csvSaved = csvSaved;
        this.csvMessage = csvMessage;
        this.rdfGenerated = rdfGenerated;
        this.rdfMessage = rdfMessage;
    }
}
