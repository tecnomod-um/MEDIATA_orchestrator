package org.taniwha.repository;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import org.taniwha.model.Project;

@Repository
public interface ProjectRepository extends MongoRepository<Project, String> {
}
