package org.taniwha.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class JobProgressDTO {
    private String jobId;
    private String state;
    private int percent;
    private String message;
}
