package org.taniwha.controller;

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
public class ErrorLogController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLogController.class);

    @PostMapping
    public ResponseEntity<String> logError(@RequestBody ErrorLogDTO errorLog) {
        logger.error("Error received from frontend:\n {}\n Info: {}\n Timestamp: {}",
                errorLog.getError(),
                errorLog.getInfo(),
                errorLog.getTimestamp());
        return ResponseEntity.ok("Error logged successfully");
    }
}
