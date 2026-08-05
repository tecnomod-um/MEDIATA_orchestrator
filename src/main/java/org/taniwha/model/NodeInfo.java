package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "nodes")
public class NodeInfo {

    @Id
    private String nodeId;
    private String ip;
    private String name;
    private String password;
    private String description;
    private String color;
    private String publicKey;
    private Boolean active;
    private Instant deregisteredAt;

    public NodeInfo(String nodeId, String ip, String name, String password,
                    String description, String color, String publicKey) {
        this.nodeId = nodeId;
        this.ip = ip;
        this.name = name;
        this.password = password;
        this.description = description;
        this.color = color;
        this.publicKey = publicKey;
    }

    public String getServiceUrl() {
        return ip;
    }

    @Override
    public String toString() {
        return "NodeInfo{" +
                "nodeId='" + nodeId + '\'' +
                ", ip='" + ip + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", color='" + color + '\'' +
                ", publicKey='" + publicKey + '\'' +
                '}';
    }
}
