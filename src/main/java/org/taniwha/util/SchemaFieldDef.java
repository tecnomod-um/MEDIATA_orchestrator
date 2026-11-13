package org.taniwha.util;

import java.util.*;

public final class SchemaFieldDef {
    private final String name;
    private final String type;
    private final String description;
    private final List<String> enumValues;

    public SchemaFieldDef(String name, String type, String description, List<String> enumValues) {
        this.name = name == null ? "" : name;
        this.type = type == null ? "" : type;
        this.description = description == null ? "" : description;
        this.enumValues = enumValues == null ? Collections.emptyList() : enumValues;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public List<String> getEnumValues() { return enumValues; }
}
