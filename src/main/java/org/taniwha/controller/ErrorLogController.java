package org.taniwha.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.dto.ErrorLogDTO;
import org.taniwha.model.ErrorLog;
import org.taniwha.repository.ErrorLogRepository;

import java.util.List;

// Receives errors from all the architecture and logs them
@RestController
@RequestMapping("/api/error")
public class ErrorLogController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLogController.class);

    private final ErrorLogRepository errorLogRepository;

    public ErrorLogController(ErrorLogRepository errorLogRepository) {
        this.errorLogRepository = errorLogRepository;
    }

    @PostMapping
    public ResponseEntity<String> logError(@RequestBody ErrorLogDTO errorLog) {
        logger.error("Error received from frontend:\n {}\n Info: {}\n Timestamp: {}",
                errorLog.getError(),
                errorLog.getInfo(),
                errorLog.getTimestamp());
        ErrorLog entity = new ErrorLog(null, errorLog.getError(), errorLog.getInfo(), errorLog.getTimestamp());
        errorLogRepository.save(entity);
        return ResponseEntity.ok("Error logged successfully");
    }

    @GetMapping("/all")
    public ResponseEntity<List<ErrorLog>> getAllLogs() {
        List<ErrorLog> logs = errorLogRepository.findAll();
        return ResponseEntity.ok(logs);
    }
}
