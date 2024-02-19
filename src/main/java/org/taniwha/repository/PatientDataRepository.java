package org.taniwha.repository;

import org.taniwha.model.PatientData;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PatientDataRepository extends JpaRepository<PatientData, Long> {
    // Custom query methods can be defined here
}
