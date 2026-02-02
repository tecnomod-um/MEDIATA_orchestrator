package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class SaveProjectRequestDTO {
    private String id;
    private String name;
    private String description;
    private int membersCount;
    private int nodesCount;
    private int dcatCount;
    private String lastAccess;
    private String badge;

    private String imageBase64;
    private String imageContentType;
}
