package org.taniwha.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ProjectDTO {
    private String id;
    private String name;
    private String description;
    private int membersCount;
    private int nodesCount;
    private int dcatCount;
    private String lastAccess;
    private String imageUrl;
    private String badge;

    public ProjectDTO() {
    }

    public ProjectDTO(String id, String name, String description, int membersCount, int nodesCount, int dcatCount, String lastAccess, String imageUrl, String badge) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.membersCount = membersCount;
        this.nodesCount = nodesCount;
        this.dcatCount = dcatCount;
        this.lastAccess = lastAccess;
        this.imageUrl = imageUrl;
        this.badge = badge;
    }

}
