package org.taniwha.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.taniwha.model.ErrorLog;

@Repository
public interface ErrorLogRepository extends MongoRepository<ErrorLog, String> {
}
