package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Service
public class FHIRService {

    private static final Logger logger = LoggerFactory.getLogger(FHIRService.class);

    public String processClusters(String jsonText) throws IOException {
        logger.info("Payload for clustering: {}", jsonText);
        ClassPathResource resource = new ClassPathResource("static/only_clusters_resQ_sk.json");
        byte[] bytes = Files.readAllBytes(resource.getFile().toPath());
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
