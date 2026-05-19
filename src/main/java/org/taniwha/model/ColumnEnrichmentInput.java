package org.taniwha.model;

import java.util.List;

public record ColumnEnrichmentInput(
        String colKey,
        String terminology,
        List<ValueSpec> values
) {}
