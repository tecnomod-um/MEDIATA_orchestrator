package org.taniwha.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.taniwha.dto.CsvCleaningRequestDTO;
import org.taniwha.dto.CsvCleaningResponseDTO;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

@Service
public class DataCleaningService {

    public CsvCleaningResponseDTO reformatCsv(CsvCleaningRequestDTO request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }

        String csvText = request.getCsvText() == null ? "" : request.getCsvText();
        char inputDelimiter = resolveDelimiter(request.getInputDelimiter(), "inputDelimiter");
        char outputDelimiter = resolveDelimiter(request.getOutputDelimiter(), "outputDelimiter");
        char inputDecimal = resolveDecimalSeparator(request.getInputDecimalSeparator(), "inputDecimalSeparator");
        char outputDecimal = resolveDecimalSeparator(request.getOutputDecimalSeparator(), "outputDecimalSeparator");

        if (csvText.isBlank()) {
            return new CsvCleaningResponseDTO("", 0, 0, "CSV reformatted successfully.");
        }

        CSVFormat inputFormat = CSVFormat.DEFAULT.withDelimiter(inputDelimiter);
        CSVFormat outputFormat = CSVFormat.DEFAULT
                .withDelimiter(outputDelimiter)
                .withRecordSeparator(System.lineSeparator());

        try (CSVParser parser = new CSVParser(new StringReader(csvText), inputFormat);
             StringWriter writer = new StringWriter();
             CSVPrinter printer = new CSVPrinter(writer, outputFormat)) {

            int rowCount = 0;
            int columnCount = 0;
            for (CSVRecord record : parser) {
                List<String> transformed = new ArrayList<>(record.size());
                for (String value : record) {
                    transformed.add(transformDecimalSeparator(value, inputDecimal, outputDecimal));
                }
                printer.printRecord(transformed);
                rowCount++;
                columnCount = Math.max(columnCount, record.size());
            }
            printer.flush();
            return new CsvCleaningResponseDTO(
                    writer.toString(),
                    rowCount,
                    columnCount,
                    "CSV reformatted successfully."
            );
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to parse or write CSV: " + e.getMessage(), e);
        }
    }

    private char resolveDelimiter(String raw, String fieldName) {
        if (raw == null || raw.isEmpty()) return ',';
        String value = raw.length() == 1 ? raw : raw.trim();
        if ("\\t".equals(value) || "TAB".equalsIgnoreCase(value)) return '\t';
        if (value.length() == 1) return value.charAt(0);
        throw new IllegalArgumentException(fieldName + " must be a single character or TAB.");
    }

    private char resolveDecimalSeparator(String raw, String fieldName) {
        String value = raw == null ? "." : raw.trim();
        if (".".equals(value) || ",".equals(value)) return value.charAt(0);
        throw new IllegalArgumentException(fieldName + " must be '.' or ','.");
    }

    private String transformDecimalSeparator(String value, char inputDecimal, char outputDecimal) {
        if (value == null || inputDecimal == outputDecimal) return value;
        String trimmed = value.trim();
        if (!looksLikeDecimalNumber(trimmed, inputDecimal)) return value;

        int decimalIndex = trimmed.lastIndexOf(inputDecimal);
        if (decimalIndex < 0) return value;

        int originalIndex = value.lastIndexOf(inputDecimal);
        if (originalIndex < 0) return value;

        return value.substring(0, originalIndex) + outputDecimal + value.substring(originalIndex + 1);
    }

    private boolean looksLikeDecimalNumber(String value, char inputDecimal) {
        if (value == null || value.isEmpty()) return false;
        if (value.indexOf(inputDecimal) < 0) return false;

        int decimalIndex = value.lastIndexOf(inputDecimal);
        if (decimalIndex <= 0 || decimalIndex >= value.length() - 1) return false;

        String left = value.substring(0, decimalIndex).trim();
        String right = value.substring(decimalIndex + 1).trim();
        if (!right.chars().allMatch(Character::isDigit)) return false;

        String normalizedLeft = left
                .replace(" ", "")
                .replace("_", "")
                .replace("'", "")
                .replace("’", "");

        if (normalizedLeft.startsWith("+") || normalizedLeft.startsWith("-")) {
            normalizedLeft = normalizedLeft.substring(1);
        }
        if (normalizedLeft.isEmpty()) return false;

        for (char c : normalizedLeft.toCharArray()) {
            if (!Character.isDigit(c) && c != '.' && c != ',') {
                return false;
            }
        }

        return normalizedLeft.chars().anyMatch(Character::isDigit);
    }
}
