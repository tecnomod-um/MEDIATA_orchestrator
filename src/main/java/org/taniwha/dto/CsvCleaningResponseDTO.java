package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CsvCleaningResponseDTO {
    private String cleanedCsv;
    private int rowCount;
    private int columnCount;
    private String message;
}
