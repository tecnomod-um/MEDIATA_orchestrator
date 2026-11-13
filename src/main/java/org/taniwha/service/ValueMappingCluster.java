package org.taniwha.service;

import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.util.StringUtil;
import java.util.*;

final class ValueMappingCluster {
    final List<SuggestedRefDTO> refs = new ArrayList<>();
    final Set<String> normalizedValues = new LinkedHashSet<>();
    final Map<String, Set<String>> normalizedValuesBySource = new HashMap<>();
    float[] centroid = null;
    int count = 0;

    void add(ValueMappingItem it) {
        refs.add(it.ref);
        normalizedValues.add(it.normalized);
        normalizedValuesBySource
                .computeIfAbsent(StringUtil.safe(it.sourceKey), k -> new HashSet<>())
                .add(it.normalized);

        if (centroid == null) {
            centroid = Arrays.copyOf(it.vec, it.vec.length);
            count = 1;
            return;
        }

        for (int i = 0; i < centroid.length; i++) {
            centroid[i] = (centroid[i] * count + it.vec[i]) / (count + 1);
        }
        count++;

        double sum = 0.0;
        for (float x : centroid) sum += (double) x * (double) x;
        double norm = Math.sqrt(sum);
        if (norm > 1e-12) {
            for (int i = 0; i < centroid.length; i++) centroid[i] = (float) (centroid[i] / norm);
        }
    }

    String repHint() {
        if (normalizedValues.isEmpty()) return "";
        return normalizedValues.iterator().next();
    }

    boolean hasDifferentValueFromSource(String sourceKey, String normalized) {
        Set<String> values = normalizedValuesBySource.get(StringUtil.safe(sourceKey));
        if (values == null || values.isEmpty()) return false;
        return !values.contains(normalized);
    }

    List<String> sampleNormVals(int n) {
        List<String> out = new ArrayList<>();
        int k = 0;
        for (String s : normalizedValues) {
            out.add(s);
            k++;
            if (k >= n) break;
        }
        return out;
    }
}
