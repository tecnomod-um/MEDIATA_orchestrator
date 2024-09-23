package org.taniwha.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.taniwha.model.NodeInfo;

@Repository
public interface NodeRepository extends MongoRepository<NodeInfo, String> {
    boolean existsByIpAndPort(String ip, int port);
    NodeInfo findByIpAndPort(String ip, int port);
}
