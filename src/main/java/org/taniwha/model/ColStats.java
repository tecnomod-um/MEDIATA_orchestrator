package org.taniwha.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class ColStats {
    private boolean hasIntegerMarker = false;
    private boolean hasDoubleMarker = false;
    private boolean hasDateMarker = false;

    private Double numMin = null;
    private Double numMax = null;

    private Long dateMinMs = null;
    private Long dateMaxMs = null;

    private Integer stepHint = null;
}
