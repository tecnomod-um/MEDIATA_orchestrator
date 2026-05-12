package org.taniwha.model;

import org.taniwha.util.StringUtil;

import java.util.List;

// Stores one dataset column together with its semantic embedding, optional value embedding, raw values, and detected stats.
public final class EmbeddedColumn {
    public final String nodeId;
    public final String fileName;
    public final String column;
    public final String concept;
    public final List<String> rawValues;
    public final float[] vec;
    public final float[] valueVec;
    public final ColStats stats;

    public EmbeddedColumn(
            String nodeId,
            String fileName,
            String column,
            String concept,
            List<String> rawValues,
            float[] vec,
            float[] valueVec,
            ColStats stats
    ) {
        this.nodeId = nodeId;
        this.fileName = fileName;
        this.column = column;
        this.concept = (concept == null || concept.trim().isEmpty()) ? StringUtil.safe(column) : concept;
        this.rawValues = rawValues;
        this.vec = vec;
        this.valueVec = valueVec;
        this.stats = (stats == null) ? new ColStats() : stats;
    }

    public String fileKey() {
        return nodeId + "::" + fileName;
    }
}