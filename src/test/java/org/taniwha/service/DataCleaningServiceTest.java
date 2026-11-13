package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.taniwha.dto.CsvCleaningRequestDTO;
import org.taniwha.dto.CsvCleaningResponseDTO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataCleaningServiceTest {

    private final DataCleaningService service = new DataCleaningService();

    @Test
    void reformatCsv_changesDelimiterAndDecimalSeparator() {
        CsvCleaningRequestDTO request = new CsvCleaningRequestDTO();
        request.setCsvText("name;value;comment\nA;1,23;ok\nB;12,50;still ok");
        request.setInputDelimiter(";");
        request.setOutputDelimiter(",");
        request.setInputDecimalSeparator(",");
        request.setOutputDecimalSeparator(".");

        CsvCleaningResponseDTO response = service.reformatCsv(request);

        assertThat(response.getCleanedCsv()).isEqualTo(
                "name,value,comment" + System.lineSeparator() +
                "A,1.23,ok" + System.lineSeparator() +
                "B,12.50,still ok" + System.lineSeparator()
        );
        assertThat(response.getRowCount()).isEqualTo(3);
        assertThat(response.getColumnCount()).isEqualTo(3);
    }

    @Test
    void reformatCsv_quotesValuesWhenOutputDelimiterCollidesWithDecimalSeparator() {
        CsvCleaningRequestDTO request = new CsvCleaningRequestDTO();
        request.setCsvText("name;value\nA;1.23");
        request.setInputDelimiter(";");
        request.setOutputDelimiter(",");
        request.setInputDecimalSeparator(".");
        request.setOutputDecimalSeparator(",");

        CsvCleaningResponseDTO response = service.reformatCsv(request);

        assertThat(response.getCleanedCsv()).isEqualTo(
                "name,value" + System.lineSeparator() +
                "A,\"1,23\"" + System.lineSeparator()
        );
    }

    @Test
    void reformatCsv_leavesNonNumericTextUntouched() {
        CsvCleaningRequestDTO request = new CsvCleaningRequestDTO();
        request.setCsvText("code;value\nBP;approx. 1,23");
        request.setInputDelimiter(";");
        request.setOutputDelimiter(";");
        request.setInputDecimalSeparator(",");
        request.setOutputDecimalSeparator(".");

        CsvCleaningResponseDTO response = service.reformatCsv(request);

        assertThat(response.getCleanedCsv()).isEqualTo(
                "code;value" + System.lineSeparator() +
                "BP;approx. 1,23" + System.lineSeparator()
        );
    }

    @Test
    void reformatCsv_rejectsInvalidDecimalSeparator() {
        CsvCleaningRequestDTO request = new CsvCleaningRequestDTO();
        request.setCsvText("a,b");
        request.setInputDecimalSeparator(";");

        assertThatThrownBy(() -> service.reformatCsv(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inputDecimalSeparator must be '.' or ','.");
    }

    @Test
    void reformatCsv_supportsSpaceAsDelimiter() {
        CsvCleaningRequestDTO request = new CsvCleaningRequestDTO();
        request.setCsvText("name value\nA 1.23");
        request.setInputDelimiter(" ");
        request.setOutputDelimiter(";");

        CsvCleaningResponseDTO response = service.reformatCsv(request);

        assertThat(response.getCleanedCsv()).isEqualTo(
                "name;value" + System.lineSeparator() +
                "A;1.23" + System.lineSeparator()
        );
    }
}
