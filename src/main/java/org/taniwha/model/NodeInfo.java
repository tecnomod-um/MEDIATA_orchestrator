package org.taniwha.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Document(collection = "nodes")
public class NodeInfo {

    @Id
    private String nodeId;
    private String ip;
    private int port;
    private String name;
    private String password;
    private String description;
    private String color;
    private String publicKey;

    public String getServiceUrl() {
        return ip + ":" + port;
    }

    @Override
    public String toString() {
        return "NodeInfo{" +
                "nodeId='" + nodeId + '\'' +
                ", ip='" + ip + '\'' +
                ", port=" + port +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", color='" + color + '\'' +
                ", publicKey='" + publicKey + '\'' +
                '}';
    }
}
