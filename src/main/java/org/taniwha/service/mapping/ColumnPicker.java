package org.taniwha.service.mapping;

import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.model.SimilarityScore;
import org.taniwha.util.MappingMathUtil;

import java.util.*;

// Picks representative source columns for schema matches and per-file mapping batches.
class ColumnPicker {

    List<EmbeddedColumn> topCols(List<SimilarityScore<EmbeddedColumn>> cs) {
        List<EmbeddedColumn> out = new ArrayList<>();
        int lim = Math.min(30, cs.size());
        for (int i = 0; i < lim; i++) out.add(cs.get(i).item());
        return out;
    }

    SimilarityScore<EmbeddedSchemaField> bestSchemaMatch(EmbeddedColumn src, List<EmbeddedSchemaField> fields) {
        EmbeddedSchemaField best = null;
        double bestSim = -1.0;

        for (EmbeddedSchemaField f : fields) {
            double sim = MappingMathUtil.cosine(src.vec, f.vec);
            if (sim > bestSim) {
                bestSim = sim;
                best = f;
            }
        }

        if (best == null) return null;
        return new SimilarityScore<>(best, bestSim);
    }

    List<List<EmbeddedColumn>> partitionMembersByFile(List<EmbeddedColumn> members, float[] centroid) {
        Map<String, List<EmbeddedColumn>> byFile = new LinkedHashMap<>();
        for (EmbeddedColumn c : members) {
            byFile.computeIfAbsent(c.fileKey(), k -> new ArrayList<>()).add(c);
        }

        for (List<EmbeddedColumn> grp : byFile.values()) {
            if (centroid != null) {
                // Keep each file ordered by its strongest candidate so round-robin batches stay meaningful.
                grp.sort((a, b) -> {
                    double sa = (a.vec == null) ? 0.0 : MappingMathUtil.cosine(a.vec, centroid);
                    double sb = (b.vec == null) ? 0.0 : MappingMathUtil.cosine(b.vec, centroid);
                    return Double.compare(sb, sa);
                });
            }
        }

        int maxRounds = 0;
        for (List<EmbeddedColumn> grp : byFile.values()) {
            maxRounds = Math.max(maxRounds, grp.size());
        }

        List<List<EmbeddedColumn>> batches = new ArrayList<>(maxRounds);
        for (int round = 0; round < maxRounds; round++) {
            List<EmbeddedColumn> batch = new ArrayList<>(byFile.size());
            for (List<EmbeddedColumn> grp : byFile.values()) {
                if (round < grp.size()) {
                    batch.add(grp.get(round));
                }
            }
            batches.add(batch);
        }
        return batches;
    }
}
