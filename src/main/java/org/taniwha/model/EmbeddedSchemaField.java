package org.taniwha.model;

import java.util.Collections;
import java.util.List;

public final class EmbeddedSchemaField {
    public final String name;
    public final String type;
    public final List<String> enumValues;
    public final float[] vec;

    public EmbeddedSchemaField(String name, String type, List<String> enumValues, float[] vec) {
        this.name = name;
        this.type = type;
        this.enumValues = (enumValues == null) ? Collections.emptyList() : enumValues;
        this.vec = vec;
    }
}
