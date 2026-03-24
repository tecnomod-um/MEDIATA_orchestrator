package org.taniwha.service.mapping;

import org.taniwha.config.MappingConfig.MappingServiceSettings;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.model.SimilarityScore;
import org.taniwha.util.MappingMathUtil;
import org.taniwha.util.StringUtil;

import java.util.*;

/**
 * Selects the best-matching {@link EmbeddedColumn} candidates given similarity scores or a
 * cluster centroid, and partitions them for multi-round cross-file batch emission.
 * <p>
 * Extracted from {@link MappingService} for maintainability.
 * </p>
 */
class ColumnPicker {

    private final MappingServiceSettings settings;

    ColumnPicker(MappingServiceSettings settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------
    // Score-based picking
    // ------------------------------------------------------------------

    /**
     * From a scored list picks the highest-scoring column per source file, then returns the
     * top {@code maxColumnsPerMapping} results sorted by score descending.
     */
    List<EmbeddedColumn> pickBestPerFileFromScores(List<SimilarityScore<EmbeddedColumn>> scored) {
        Map<String, SimilarityScore<EmbeddedColumn>> best = new LinkedHashMap<>();

        for (SimilarityScore<EmbeddedColumn> s : scored) {
            EmbeddedColumn col = s.item();
            String fk = col.fileKey();
            SimilarityScore<EmbeddedColumn> cur = best.get(fk);
            if (cur == null || s.sim() > cur.sim()) {
                best.put(fk, s);
            }
        }

        List<SimilarityScore<EmbeddedColumn>> list = new ArrayList<>(best.values());
        list.sort((a, b) -> Double.compare(b.sim(), a.sim()));
        List<EmbeddedColumn> out = new ArrayList<>();
        for (SimilarityScore<EmbeddedColumn> s : list) {
            if (out.size() >= settings.maxColumnsPerMapping()) break;
            out.add(s.item());
        }
        return out;
    }

    /**
     * From a plain column list picks the column per source file that is closest to
     * {@code centroid}, returning at most {@code cap} results.
     */
    List<EmbeddedColumn> pickBestPerFileFromCols(List<EmbeddedColumn> cols, float[] centroid, int cap) {
        Map<String, EmbeddedColumn> best = new LinkedHashMap<>();
        Map<String, Double> bestSim = new HashMap<>();

        for (EmbeddedColumn c : cols) {
            String fk = c.fileKey();
            double sim = (centroid == null) ? 0.0 : MappingMathUtil.cosine(c.vec, centroid);

            EmbeddedColumn cur = best.get(fk);
            if (cur == null) {
                best.put(fk, c);
                bestSim.put(fk, sim);
                continue;
            }

            double curSim = bestSim.getOrDefault(fk, -1.0);
            if (sim > curSim + 1e-9) {
                best.put(fk, c);
                bestSim.put(fk, sim);
            } else if (Math.abs(sim - curSim) <= 1e-9) {
                String a = StringUtil.safe(c.column);
                String b = StringUtil.safe(cur.column);
                if (a.length() < b.length() || (a.length() == b.length() && a.compareToIgnoreCase(b) < 0)) {
                    best.put(fk, c);
                    bestSim.put(fk, sim);
                }
            }
        }

        List<EmbeddedColumn> out = new ArrayList<>(best.values());
        out.sort((a, b) -> {
            double sa = bestSim.getOrDefault(a.fileKey(), 0.0);
            double sb = bestSim.getOrDefault(b.fileKey(), 0.0);
            int x = Double.compare(sb, sa);
            if (x != 0) return x;
            x = a.fileName.compareToIgnoreCase(b.fileName);
            if (x != 0) return x;
            return a.column.compareToIgnoreCase(b.column);
        });

        if (out.size() > cap) out = out.subList(0, cap);
        return out;
    }

    /**
     * Returns the top-30 (at most) columns from a scored list, in score order.
     */
    List<EmbeddedColumn> topCols(List<SimilarityScore<EmbeddedColumn>> cs) {
        List<EmbeddedColumn> out = new ArrayList<>();
        int lim = Math.min(30, cs.size());
        for (int i = 0; i < lim; i++) out.add(cs.get(i).item());
        return out;
    }

    // ------------------------------------------------------------------
    // Schema matching
    // ------------------------------------------------------------------

    /**
     * Returns the schema field with the highest cosine similarity to {@code src}, or
     * {@code null} when {@code fields} is empty.
     */
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

    // ------------------------------------------------------------------
    // Batch partitioning
    // ------------------------------------------------------------------

    /**
     * Groups {@code members} by source file and produces round-robin batches, each containing
     * exactly one column per file.
     *
     * <p>Files with only one candidate ("anchor" files) contribute that same column in every
     * round so that every batch remains a complete cross-file join.  Files with multiple
     * candidates (e.g. {@code ToiletBART1} and {@code ToiletBART2}) rotate through them across
     * rounds — round 0 gets the best match, round 1 the next-best, etc.</p>
     *
     * <p>Within each file group, columns are sorted by cosine similarity to {@code centroid}
     * (highest first) when a centroid is provided.</p>
     */
    List<List<EmbeddedColumn>> partitionMembersByFile(List<EmbeddedColumn> members, float[] centroid) {
        Map<String, List<EmbeddedColumn>> byFile = new LinkedHashMap<>();
        for (EmbeddedColumn c : members) {
            byFile.computeIfAbsent(c.fileKey(), k -> new ArrayList<>()).add(c);
        }

        // Sort each file group by similarity to centroid (best first).
        for (List<EmbeddedColumn> grp : byFile.values()) {
            if (centroid != null) {
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
                // Anchor files reuse their best column once their list is exhausted.
                batch.add(grp.get(Math.min(round, grp.size() - 1)));
            }
            batches.add(batch);
        }
        return batches;
    }
}
