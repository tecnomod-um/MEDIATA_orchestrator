package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CsvCleaningRequestDTO {
    private String csvText;
    private String inputDelimiter;
    private String outputDelimiter;
    private String inputDecimalSeparator;
    private String outputDecimalSeparator;
}
