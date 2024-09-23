package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErrorLogDTO {
    private String error;
    private String info;
    private String timestamp;
}
