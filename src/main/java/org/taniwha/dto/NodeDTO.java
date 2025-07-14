package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NodeDTO {

    private String nodeId;
    private String ip;
    private String name;
    private String password;
    private String description;
    private String color;
    private String publicKey;
}
