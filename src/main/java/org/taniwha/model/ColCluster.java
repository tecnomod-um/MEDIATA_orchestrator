package org.taniwha.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ColCluster {
    public final List<EmbeddedColumn> cols = new ArrayList<>();
    public float[] centroid = null;
    public int count = 0;
    public String representativeConcept = "";

    public void add(EmbeddedColumn col) {
        cols.add(col);

        if (centroid == null) {
            centroid = Arrays.copyOf(col.vec, col.vec.length);
            count = 1;
            return;
        }

        for (int i = 0; i < centroid.length; i++) {
            centroid[i] = (centroid[i] * count + col.vec[i]) / (count + 1);
        }
        count++;

        double sum = 0.0;
        for (float x : centroid) sum += (double) x * (double) x;
        double norm = Math.sqrt(sum);
        if (norm > 1e-12) {
            for (int i = 0; i < centroid.length; i++) centroid[i] = (float) (centroid[i] / norm);
        }
    }
}
