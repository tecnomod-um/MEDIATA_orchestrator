package org.taniwha.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Document(collection = "projects")
public class Project {

    @Id
    private String id;
    private String name;
    private String description;
    private String badge;
    private List<String> nodeIds = new ArrayList<>();
    private byte[] imageBytes;
    private String imageContentType;
}
