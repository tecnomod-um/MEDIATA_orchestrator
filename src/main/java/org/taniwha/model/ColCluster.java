package org.taniwha.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ColCluster {
    public final List<EmbeddedColumn> cols = new ArrayList<>();
    public float[] centroid = null;
    /**
     * Running centroid of the values-only embeddings ({@link EmbeddedColumn#valueVec}) for
     * categorical member columns.  {@code null} when no member carries a non-null
     * {@code valueVec} (e.g. a cluster that contains only numeric columns).
     */
    public float[] valueCentroid = null;
    public int count = 0;
    private int valueCentroidCount = 0;
    public String representativeConcept = "";

    public void add(EmbeddedColumn col) {
        cols.add(col);

        // Update the combined-embedding centroid.
        if (centroid == null) {
            centroid = Arrays.copyOf(col.vec, col.vec.length);
            count = 1;
        } else {
            for (int i = 0; i < centroid.length; i++) {
                centroid[i] = (centroid[i] * count + col.vec[i]) / (count + 1);
            }
            count++;
            normalizeInPlace(centroid);
        }

        // Update the values-only centroid (only for columns that carry one).
        if (col.valueVec != null) {
            if (valueCentroid == null) {
                valueCentroid = Arrays.copyOf(col.valueVec, col.valueVec.length);
                valueCentroidCount = 1;
            } else {
                for (int i = 0; i < valueCentroid.length; i++) {
                    valueCentroid[i] = (valueCentroid[i] * valueCentroidCount + col.valueVec[i])
                            / (valueCentroidCount + 1);
                }
                valueCentroidCount++;
                normalizeInPlace(valueCentroid);
            }
        }
    }

    private static void normalizeInPlace(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += (double) x * (double) x;
        double norm = Math.sqrt(sum);
        if (norm > 1e-12) {
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        }
    }
}
