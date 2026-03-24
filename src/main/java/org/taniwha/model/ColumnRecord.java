package org.taniwha.model;

import java.util.List;

public record ColumnRecord(String colKey, List<String> values) {}