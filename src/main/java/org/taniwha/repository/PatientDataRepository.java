package org.taniwha.repository;

import org.springframework.stereotype.Repository;
import org.taniwha.model.PatientData;
import org.springframework.data.jpa.repository.JpaRepository;

@Repository
public interface PatientDataRepository extends JpaRepository<PatientData, Long> {
}
