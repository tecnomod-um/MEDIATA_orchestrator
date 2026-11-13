package org.taniwha.service;

import org.taniwha.dto.SuggestedRefDTO;
import java.util.*;

final class ValueMappingItem {
    final String normalized;
    final float[] vec;
    final SuggestedRefDTO ref;
    final String sourceKey;

    ValueMappingItem(String normalized, float[] vec, SuggestedRefDTO ref, String sourceKey) {
        this.normalized = normalized;
        this.vec = vec;
        this.ref = ref;
        this.sourceKey = sourceKey;
    }
}
