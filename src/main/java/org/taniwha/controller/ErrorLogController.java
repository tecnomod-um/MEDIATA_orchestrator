package org.taniwha.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.taniwha.dto.ErrorLogDTO;

@RestController
@RequestMapping("/api/error")
public class ErrorLogController {

    private static final Logger logger = LoggerFactory.getLogger(ErrorLogController.class);

    @PostMapping
    @Async
    public ResponseEntity<String> logError(@RequestBody ErrorLogDTO errorLog) {
        logger.error("Error received from frontend:\n {}\n Info: {}\n Timestamp: {}",
                errorLog.getError(),
                errorLog.getInfo(),
                errorLog.getTimestamp());

        return ResponseEntity.ok("Error logged successfully");
    }
}
