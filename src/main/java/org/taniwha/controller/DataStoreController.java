package org.taniwha.controller;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.taniwha.model.PatientData;
import org.taniwha.repository.PatientDataRepository;

@RestController
@RequestMapping("/api/patientdata")
public class DataStoreController {

    @Autowired
    private PatientDataRepository repository;

    @GetMapping
    public List<PatientData> getAll() {
        return repository.findAll();
    }

    @PostMapping
    public PatientData create(@RequestBody PatientData data) {
        return repository.save(data);
    }

    @GetMapping("/count")
    public ResponseEntity<Long> countDataPeeks() {
        long count = repository.count();
        return ResponseEntity.ok(count);
    }
}
