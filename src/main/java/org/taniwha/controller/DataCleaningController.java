package org.taniwha.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.CsvCleaningRequestDTO;
import org.taniwha.dto.CsvCleaningResponseDTO;
import org.taniwha.service.DataCleaningService;

@RestController
@RequestMapping("/api/data-cleaning")
public class DataCleaningController {

    private final DataCleaningService dataCleaningService;

    public DataCleaningController(DataCleaningService dataCleaningService) {
        this.dataCleaningService = dataCleaningService;
    }

    @PostMapping("/csv/reformat")
    public ResponseEntity<CsvCleaningResponseDTO> reformatCsv(@RequestBody CsvCleaningRequestDTO request) {
        return ResponseEntity.ok(dataCleaningService.reformatCsv(request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleInvalidCsvOptions(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }
}
