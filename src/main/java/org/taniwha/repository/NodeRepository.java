package org.taniwha.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.taniwha.model.NodeInfo;

import java.util.List;

@Repository
public interface NodeRepository extends MongoRepository<NodeInfo, String> {
    boolean existsByIp(String ip);

    NodeInfo findByIp(String ip);

    List<NodeInfo> findAllByIp(String ip);
}
