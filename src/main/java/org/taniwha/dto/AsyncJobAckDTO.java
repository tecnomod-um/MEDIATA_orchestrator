package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AsyncJobAckDTO {
    private String jobId;
    private boolean progress;
    private String message;
}
