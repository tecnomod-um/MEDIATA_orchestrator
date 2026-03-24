package org.taniwha.model;

public final class ColStats {
    public boolean hasIntegerMarker = false;
    public boolean hasDoubleMarker = false;
    public boolean hasDateMarker = false;

    public Double numMin = null;
    public Double numMax = null;

    public Long dateMinMs = null;
    public Long dateMaxMs = null;

    public Integer stepHint = null;
}
