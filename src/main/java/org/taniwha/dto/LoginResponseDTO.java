package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResponseDTO {

    private String token;
    private String tgt;

    public LoginResponseDTO(String token, String tgt) {
        this.token = token;
        this.tgt = tgt;
    }
}
