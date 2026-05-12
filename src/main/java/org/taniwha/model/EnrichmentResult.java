package org.taniwha.model;

import java.util.Map;

public record EnrichmentResult(
        String colDesc,
        Map<String, String> valueDescByValue
) {}
