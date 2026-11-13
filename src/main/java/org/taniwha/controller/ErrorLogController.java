package org.taniwha.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.taniwha.dto.ErrorLogDTO;

// Receives errors from all the architecture and logs them
@RestController
@RequestMapping("/api/error")
@Tag(name = "Client errors", description = "Receive frontend error reports for operational diagnosis.")
public class ErrorLogController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLogController.class);

    @Operation(summary = "Record a frontend error report")
    @PostMapping
    public ResponseEntity<String> logError(@RequestBody ErrorLogDTO errorLog) {
        logger.error("Error received from frontend:\n {}\n Info: {}\n Timestamp: {}",
                errorLog.getError(),
                errorLog.getInfo(),
                errorLog.getTimestamp());
        return ResponseEntity.ok("Error logged successfully");
    }
}
