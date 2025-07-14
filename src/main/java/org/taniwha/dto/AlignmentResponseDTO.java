package org.taniwha.dto;

public class AlignmentResponseDTO {
    private boolean csvSaved;
    private String csvMessage;
    private boolean rdfGenerated;
    private String rdfMessage;

    public AlignmentResponseDTO() {}

    public AlignmentResponseDTO(boolean csvSaved, String csvMessage,
                                boolean rdfGenerated, String rdfMessage) {
        this.csvSaved = csvSaved;
        this.csvMessage = csvMessage;
        this.rdfGenerated = rdfGenerated;
        this.rdfMessage = rdfMessage;
    }
    // getters + setters omitted for brevity
}
