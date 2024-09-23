package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RegisterRequestDTO {
    private String username;
    private String password;
    private String email;
    private List<String> roles;
}
