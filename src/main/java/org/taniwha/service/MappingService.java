package org.taniwha.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.taniwha.dto.ElementFileDTO;
import org.taniwha.dto.MappingSuggestRequestDTO;
import org.taniwha.dto.OntologyTermDTO;
import org.taniwha.dto.SuggestedGroupDTO;
import org.taniwha.dto.SuggestedMappingDTO;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.util.JsonSchemaParsingUtil;
import org.taniwha.util.NormalizationUtil;
import org.taniwha.util.StringUtil;
import org.taniwha.util.MappingMathUtil;

import java.util.*;

@Service
public class MappingService {

    private static final Logger logger = LoggerFactory.getLogger(MappingService.class);

    private final EmbeddingsClient embeddingsClient;
    private final RDFService rdfService;
    private final DescriptionGenerator descriptionGenerator;

    private static final java.util.concurrent.atomic.AtomicInteger embeddingLogCount = 
        new java.util.concurrent.atomic.AtomicInteger(0);

    public MappingService(EmbeddingsClient embeddingsClient, 
                         RDFService rdfService,
                         DescriptionGenerator descriptionGenerator) {
        this.embeddingsClient = embeddingsClient;
        this.rdfService = rdfService;
        this.descriptionGenerator = descriptionGenerator;
        logger.info("[MappingService] Initialized with EmbeddingsClient: {}, RDFService: {}, DescriptionGenerator: {}", 
            embeddingsClient != null ? "present" : "NULL!",
            rdfService != null ? "present" : "NULL!",
            descriptionGenerator != null ? "present" : "NULL!");
    }

    private static final int MAX_VALUES = 80;
    private static final int MAX_ENUM = 120;

    private static final double DEFAULT_W_SRC_NAME = 0.70;
    private static final double DEFAULT_W_SRC_VALUES = 0.30;

    private static final double W_TGT_NAME = 0.70;
    private static final double W_TGT_ENUM = 0.30;

    private static final double THRESH_SCHEMA_COL = 0.33;
    private static final double THRESH_ENUM_VALUE = 0.40;

    private static final double THRESH_CLUSTER = 0.55;

    private static final int MAX_VALUES_PER_UNION = 220;
    private static final int MAX_SUGGESTIONS_PER_SCHEMA_FIELD = 6;

    private static final double THRESH_COL_CLUSTER = 0.56;
    private static final int MAX_SCHEMALESS_TARGETS = 40;

    // one-column-per-file
    private static final int MAX_COLS_PER_MAPPING = 10;

    private static final int MAX_ORDINAL_CATEGORIES = 60;

    private static final int MAX_DOMAIN_UNIQUE = 25;
    private static final int MIN_DOMAIN_UNIQUE = 2;
    private static final double THRESH_TOKEN_ALIAS = 0.72;
    private static final int MIN_SUPPORT_FOR_CANON = 2;

    // learned noise thresholds (computed per request)
    private static final double NOISE_DF_FRACTION = 0.22;     // token appears in >=22% of columns => noise
    private static final int NOISE_DF_MIN_COUNT = 4;          // or appears in >=4 columns
    private static final int SUFFIX_NOISE_MIN_COUNT = 3;      // token appears as suffix in >=3 columns

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<Map<String, SuggestedMappingDTO>> suggestMappings(MappingSuggestRequestDTO req) {
        // Reset embedding log counter for this request
        embeddingLogCount.set(0);
        
        List<ElementFileDTO> cols = (req == null || req.getElementFiles() == null)
                ? Collections.emptyList()
                : req.getElementFiles();

        logger.info("[TRACE] suggestMappings called with {} element files", cols.size());
        if (cols.isEmpty()) return Collections.emptyList();

        // --- Prepass: collect column-name tokens to learn request-specific noise ---
        List<RawColName> rawNames = new ArrayList<>(cols.size());
        for (ElementFileDTO c : cols) {
            if (c == null) continue;
            String colName = StringUtil.safe(c.getColumn());
            if (!colName.trim().isEmpty()) {
                rawNames.add(new RawColName(colName, tokenizeName(colName)));
            }
        }

        LearnedNoise learnedNoise = learnNoiseFromRequest(rawNames);

        // --- Build ColRefs with learned canonicalization + adaptive weights ---
        List<ColRef> all = new ArrayList<>(cols.size());
        for (ElementFileDTO c : cols) {
            if (c == null) continue;

            String nodeId = StringUtil.safe(c.getNodeId());
            String fileName = StringUtil.safe(c.getFileName());
            String colName = StringUtil.safe(c.getColumn());
            List<String> rawValues = c.getValues();

            ColStats stats = parseStatsFromValues(rawValues);

            CanonicalName can = canonicalConceptName(colName, learnedNoise);
            
            // Log first 10 canonical concepts to see what's happening
            if (all.size() < 10) {
                logger.info("[TRACE] Column '{}' -> canonical concept '{}'", colName, can.concept);
            }

            // LLM Approach: Create a single rich embedding with column name + values
            // This allows the LLM to understand context like:
            // "Type: Ischemic, Hemorrhagic" matching with "Etiology: Hem, HEM, Isch"
            float[] combined = embedColumnWithValues(can.concept, rawValues, stats);
            
            if (combined == null || combined.length == 0) {
                logger.error("[MappingService] Failed to embed column '{}' - got null/empty embedding!", colName);
                continue;
            }

            all.add(new ColRef(nodeId, fileName, colName, can.concept, rawValues, combined, stats));
        }
        if (all.isEmpty()) return Collections.emptyList();

        List<SchemaFieldRef> schemaFields = parseSchemaFields(req == null ? null : req.getSchema());
        if (!schemaFields.isEmpty()) return suggestWithSchema(schemaFields, all);

        logger.info("[MappingService] No usable schema fields provided. Using schema-less suggestion mode.");
        return suggestWithoutSchema(all);
    }

    // ============================================================
    // Schema mode
    // ============================================================

    private List<Map<String, SuggestedMappingDTO>> suggestWithSchema(List<SchemaFieldRef> schemaFields, List<ColRef> all) {
        Map<String, List<ColScore>> colsBySchema = new HashMap<>();

        for (ColRef src : all) {
            SchemaScore best = bestSchemaMatch(src, schemaFields);
            if (best == null) continue;
            if (best.sim < THRESH_SCHEMA_COL) continue;

            colsBySchema.computeIfAbsent(best.field.name, k -> new ArrayList<>())
                    .add(new ColScore(src, best.sim));
        }

        if (colsBySchema.isEmpty()) return Collections.emptyList();

        Set<String> usedUnionKeys = new HashSet<>();
        List<Map<String, SuggestedMappingDTO>> out = new ArrayList<>();

        for (SchemaFieldRef field : schemaFields) {
            List<ColScore> candidates = colsBySchema.get(field.name);
            if (candidates == null || candidates.isEmpty()) continue;

            candidates.sort((a, b) -> Double.compare(b.sim, a.sim));

            String schemaType = StringUtil.safe(field.type);
            String detectedFieldType = detectTypeFromSourcesOrSchema(topCols(candidates, 30), schemaType);

            List<List<ColScore>> batches = partitionTopCandidates(candidates, 80);

            int emitted = 0;
            for (List<ColScore> batch : batches) {
                if (emitted >= MAX_SUGGESTIONS_PER_SCHEMA_FIELD) break;

                List<ColRef> picked = pickBestPerFileFromScores(batch, MAX_COLS_PER_MAPPING);
                if (picked.isEmpty()) continue;

                String unionKey = makeUnique(sanitizeUnionName(field.name), usedUnionKeys);

                SuggestedMappingDTO mapping = buildMappingSkeleton("standard", "suggested_mapping", picked);

                SuggestedGroupDTO group = new SuggestedGroupDTO();
                group.setColumn(unionKey);

                List<SuggestedValueDTO> values = buildValuesForConcept(unionKey, detectedFieldType, field, picked);
                if (values.isEmpty()) continue;

                group.setValues(values);
                mapping.setGroups(Collections.singletonList(group));

                Map<String, SuggestedMappingDTO> one = new LinkedHashMap<>();
                one.put(unionKey, mapping);
                out.add(one);

                emitted++;
            }
        }

        return out;
    }

    private List<List<ColScore>> partitionTopCandidates(List<ColScore> candidates, int maxTake) {
        int take = Math.min(maxTake, candidates.size());
        List<ColScore> top = new ArrayList<>(candidates.subList(0, take));
        List<List<ColScore>> out = new ArrayList<>();
        out.add(top);
        return out;
    }

    // ============================================================
    // Schema-less mode
    // ============================================================

    private List<Map<String, SuggestedMappingDTO>> suggestWithoutSchema(List<ColRef> all) {
        logger.info("[TRACE] suggestWithoutSchema: clustering {} columns", all.size());
        List<ColCluster> clusters = clusterColumns(all);
        logger.info("[TRACE] suggestWithoutSchema: formed {} clusters", clusters.size());

        clusters.sort((a, b) -> {
            int as = a.cols.size(), bs = b.cols.size();
            if (as != bs) return Integer.compare(bs, as);
            return a.representativeConcept.compareToIgnoreCase(b.representativeConcept);
        });

        if (clusters.size() > MAX_SCHEMALESS_TARGETS) {
            logger.info("[TRACE] suggestWithoutSchema: truncating from {} to {} clusters", clusters.size(), MAX_SCHEMALESS_TARGETS);
            clusters = clusters.subList(0, MAX_SCHEMALESS_TARGETS);
        }

        Set<String> usedUnionKeys = new HashSet<>();
        List<Map<String, SuggestedMappingDTO>> out = new ArrayList<>();

        for (ColCluster cl : clusters) {
            if (cl.cols.isEmpty()) continue;

            String concept = sanitizeUnionName(cl.representativeConcept);
            if (concept.isEmpty()) continue;

            List<ColRef> members = new ArrayList<>(cl.cols);

            // FIX: one column per file (nodeId+fileName), pick best match to cluster centroid
            List<ColRef> picked = pickBestPerFileFromCols(members, cl.centroid, MAX_COLS_PER_MAPPING);
            if (picked.isEmpty()) continue;

            String detectedType = detectTypeFromSourcesOrSchema(picked, "");

            String unionKey = makeUnique(concept, usedUnionKeys);
            
            logger.info("[TRACE] Cluster {}/{}: key='{}', representative='{}', {} cols, {} picked", 
                out.size() + 1, clusters.size(), unionKey, cl.representativeConcept, members.size(), picked.size());

            SuggestedMappingDTO mapping = buildMappingSkeleton("standard", "suggested_mapping", picked);

            SuggestedGroupDTO group = new SuggestedGroupDTO();
            group.setColumn(unionKey);

            List<SuggestedValueDTO> values = buildValuesForConcept(unionKey, detectedType, null, picked);
            if (values.isEmpty()) continue;

            group.setValues(values);
            mapping.setGroups(Collections.singletonList(group));

            Map<String, SuggestedMappingDTO> one = new LinkedHashMap<>();
            one.put(unionKey, mapping);
            out.add(one);
        }

        return out;
    }

    // ============================================================
    // JOIN RULE (one column per file)
    // ============================================================

    private List<ColRef> pickBestPerFileFromScores(List<ColScore> scored, int cap) {
        Map<String, ColScore> best = new LinkedHashMap<>();
        for (ColScore cs : scored) {
            String fk = cs.col.fileKey();
            ColScore cur = best.get(fk);
            if (cur == null || cs.sim > cur.sim) best.put(fk, cs);
        }

        List<ColScore> list = new ArrayList<>(best.values());
        list.sort((a, b) -> Double.compare(b.sim, a.sim));

        List<ColRef> out = new ArrayList<>();
        for (ColScore cs : list) {
            if (out.size() >= cap) break;
            out.add(cs.col);
        }
        return out;
    }

    private List<ColRef> pickBestPerFileFromCols(List<ColRef> cols, float[] centroid, int cap) {
        Map<String, ColRef> best = new LinkedHashMap<>();
        Map<String, Double> bestSim = new HashMap<>();

        for (ColRef c : cols) {
            String fk = c.fileKey();
            double sim = (centroid == null) ? 0.0 : MappingMathUtil.cosine(c.vec, centroid);

            ColRef cur = best.get(fk);
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
                // tie-breaker: prefer "cleaner" (shorter) column names
                String a = StringUtil.safe(c.column);
                String b = StringUtil.safe(cur.column);
                if (a.length() < b.length() || (a.length() == b.length() && a.compareToIgnoreCase(b) < 0)) {
                    best.put(fk, c);
                    bestSim.put(fk, sim);
                }
            }
        }

        List<ColRef> out = new ArrayList<>(best.values());
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

    // ============================================================
    // Values
    // ============================================================

    private List<SuggestedValueDTO> buildValuesForConcept(
            String unionKey,
            String detectedType,
            SchemaFieldRef schemaFieldOrNull,
            List<ColRef> picked
    ) {
        String dt = StringUtil.safe(detectedType).toLowerCase(Locale.ROOT);

        if ("date".equals(dt)) {
            return buildRangeValue(unionKey, "date", picked);
        }

        if ("integer".equals(dt) || "double".equals(dt)) {
            // Ordinal only when all participating sources are integer-ordinal compatible
            List<SuggestedValueDTO> ordinal = buildOrdinalNumericCrosswalkAsNonOverlappingRanges(unionKey, dt, picked);
            if (!ordinal.isEmpty()) return ordinal;

            return buildRangeValue(unionKey, dt, picked);
        }

        if (schemaFieldOrNull != null && schemaFieldOrNull.enumValues != null && !schemaFieldOrNull.enumValues.isEmpty()) {
            List<SuggestedValueDTO> ev = buildEnumValueMappings(schemaFieldOrNull, picked);
            if (!ev.isEmpty()) return ev;
        }

        List<SuggestedValueDTO> harmonized = buildClosedDomainCategorical(unionKey, picked);
        if (!harmonized.isEmpty()) return harmonized;

        return buildClusteredValueMappings(unionKey, picked);
    }

    // ============================================================
    // Numeric ordinal crosswalk (NON-OVERLAPPING, FULL-COVERAGE integer intervals)
    // ============================================================

    private List<SuggestedValueDTO> buildOrdinalNumericCrosswalkAsNonOverlappingRanges(
            String unionKey,
            String detectedType,
            List<ColRef> sources
    ) {
        // only integer ordinal crosswalk
        if (!"integer".equalsIgnoreCase(StringUtil.safe(detectedType))) return Collections.emptyList();

        List<ScaleDomain> domains = new ArrayList<>();
        for (ColRef s : sources) {
            ScaleDomain d = inferScaleDomain(s);
            if (d != null) domains.add(d);
        }
        if (domains.size() < 2) return Collections.emptyList();

        // IMPORTANT: map towards the encoding with fewer values to avoid "making up" extra buckets.
        ScaleDomain canon = pickCanonicalScaleDomainPreferFewer(domains);
        if (canon == null) return Collections.emptyList();
        if (canon.categories.size() < 2 || canon.categories.size() > MAX_ORDINAL_CATEGORIES) return Collections.emptyList();

        List<SuggestedValueDTO> out = new ArrayList<>();
        int nCanon = canon.categories.size();

        for (int i = 0; i < nCanon; i++) {
            int canonCat = canon.categories.get(i);

            List<SuggestedRefDTO> refs = new ArrayList<>();
            for (ColRef src : sources) {
                ScaleDomain srcDom = inferScaleDomain(src);
                if (srcDom == null) continue;
                if (srcDom.categories.size() < 2) continue;

                RangeIdx r = mapCanonBucketToSourceIndexRangeByPartition(i, nCanon, srcDom.categories.size());

                int loIdx = MappingMathUtil.clamp(r.lo, 0, srcDom.categories.size() - 1);
                int hiIdx = MappingMathUtil.clamp(r.hi, 0, srcDom.categories.size() - 1);
                if (hiIdx < loIdx) { int tmp = loIdx; loIdx = hiIdx; hiIdx = tmp; }

                SuggestedRefDTO ref = new SuggestedRefDTO();
                ref.setNodeId(src.nodeId);
                ref.setFileName(src.fileName);
                ref.setGroupColumn(src.column);
                ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));

                // Example cats [0,5,10,15] => idx 2 => 10..14, idx 3 => 15..15.
                ref.setValue(idxRangeToNumericInterval(srcDom.categories, loIdx, hiIdx));
                refs.add(ref);
            }

            if (refs.isEmpty()) continue;

            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(String.valueOf(canonCat));
            
            // Add terminology and description for ordinal crosswalk value
            populateValueTerminologyAndDescription(vd, String.valueOf(canonCat), 
                unionKey, getAllValuesForContext(sources));
            
            vd.setMapping(refs);
            out.add(vd);
        }

        out.sort(Comparator.comparingInt(a -> MappingMathUtil.safeInt(a.getName(), Integer.MIN_VALUE)));
        return out;
    }

    private RangeIdx mapCanonBucketToSourceIndexRangeByPartition(int canonIndex, int canonSize, int srcSize) {
        if (canonSize <= 1 || srcSize <= 1) return new RangeIdx(0, 0);

        // Assign each source index to a canonical bucket using proportional rounding, then take min/max index per bucket.
        List<List<Integer>> assigned = new ArrayList<>(canonSize);
        for (int i = 0; i < canonSize; i++) assigned.add(new ArrayList<>());

        double denom = (double) (srcSize - 1);
        double num = (double) (canonSize - 1);

        for (int j = 0; j < srcSize; j++) {
            int ci = (int) Math.round((j / denom) * num);
            if (ci < 0) ci = 0;
            if (ci >= canonSize) ci = canonSize - 1;
            assigned.get(ci).add(j);
        }

        List<Integer> bucket = assigned.get(canonIndex);
        if (bucket.isEmpty()) {
            int nearest = (int) Math.round(((double) canonIndex / (double) (canonSize - 1)) * (srcSize - 1));
            nearest = MappingMathUtil.clamp(nearest, 0, srcSize - 1);
            return new RangeIdx(nearest, nearest);
        }

        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int j : bucket) {
            if (j < lo) lo = j;
            if (j > hi) hi = j;
        }
        return new RangeIdx(lo, hi);
    }

    private static final class RangeIdx {
        final int lo;
        final int hi;

        RangeIdx(int lo, int hi) {
            this.lo = lo;
            this.hi = hi;
        }
    }

    private Map<String, Object> makeRangeValue(double minValue, double maxValue, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minValue", minValue);
        m.put("maxValue", maxValue);
        m.put("type", type);
        return m;
    }

    // Recognize common ordinal scale shapes from min/max (+ optional tokens)
    // (No hardcoded name tokens; only uses numeric/stat hints.)
    private ScaleDomain inferScaleDomain(ColRef src) {
        if (src == null || src.stats == null) return null;
        if (!src.stats.hasIntegerMarker) return null;
        if (src.stats.numMin == null || src.stats.numMax == null) return null;

        int min = (int) Math.round(src.stats.numMin);
        int max = (int) Math.round(src.stats.numMax);
        if (max < min) return null;

        Integer hintStep = src.stats.stepHint;

        // FIM item: 1..7
        if (min == 1 && max == 7) {
            return ScaleDomain.linear(src, 1, 7, 1, ScaleKind.FIM, "shape(1..7)");
        }

        // Barthel item: 0..5/10/15 step 5
        if (min == 0 && (max == 5 || max == 10 || max == 15)) {
            return ScaleDomain.linear(src, 0, max, 5, ScaleKind.BARTHEL_ITEM, "shape(0.." + max + " step5)");
        }

        // Barthel total: 0..100 step from hint or default 5
        if (min == 0 && max == 100) {
            int step = (hintStep != null && hintStep > 0) ? hintStep : 5;
            return ScaleDomain.linear(src, 0, 100, step, ScaleKind.BARTHEL_TOTAL, "shape(0..100 step" + step + ")");
        }

        // Generic small ordinal integer domain
        int span = max - min;
        if (span >= 1 && span <= 20) {
            return ScaleDomain.linear(src, min, max, 1, ScaleKind.GENERIC, "shape(" + min + ".." + max + ")");
        }

        return null;
    }

    private Map<String, Object> idxRangeToNumericInterval(List<Integer> cats, int loIdx, int hiIdx) {
        if (cats == null || cats.isEmpty()) return makeRangeValue(0, 0, "integer");

        int lo = MappingMathUtil.clamp(loIdx, 0, cats.size() - 1);
        int hi = MappingMathUtil.clamp(hiIdx, 0, cats.size() - 1);
        if (hi < lo) { int tmp = lo; lo = hi; hi = tmp; }

        int min = cats.get(lo);

        int max;
        if (hi >= cats.size() - 1) {
            // last bucket ends at the last code itself (cannot infer beyond observed code list)
            max = cats.get(cats.size() - 1);
        } else {
            int next = cats.get(hi + 1);
            max = next - 1;
            if (max < min) max = min;
        }

        return makeRangeValue(min, max, "integer");
    }

    private ScaleDomain pickCanonicalScaleDomainPreferFewer(List<ScaleDomain> domains) {
        if (domains == null || domains.isEmpty()) return null;

        ScaleDomain best = null;
        for (ScaleDomain d : domains) {
            if (d == null) continue;
            if (best == null) {
                best = d;
                continue;
            }

            int dc = d.categories.size();
            int bc = best.categories.size();

            if (dc < bc) {
                best = d; // fewer values wins
            } else if (dc == bc) {
                if (kindRank(d.kind) > kindRank(best.kind)) best = d;
            }
        }
        return best;
    }

    private int kindRank(ScaleKind k) {
        if (k == ScaleKind.BARTHEL_ITEM) return 4;
        if (k == ScaleKind.FIM) return 3;
        if (k == ScaleKind.BARTHEL_TOTAL) return 2;
        return 1;
    }

    private enum ScaleKind { FIM, BARTHEL_ITEM, BARTHEL_TOTAL, GENERIC }

    private static final class ScaleDomain {
        final ScaleKind kind;
        final List<Integer> categories;
        final String debug;

        private ScaleDomain(ScaleKind kind, List<Integer> categories, String debug) {
            this.kind = kind;
            this.categories = categories;
            this.debug = debug;
        }

        static ScaleDomain linear(ColRef src, int min, int max, int step, ScaleKind kind, String note) {
            List<Integer> cats = new ArrayList<>();
            int st = Math.max(step, 1);
            for (int v = min; v <= max; v += st) cats.add(v);
            String dbg = src.fileName + ":" + src.column + " " + note;
            return new ScaleDomain(kind, cats, dbg);
        }
    }

    // ============================================================
    // Generic closed-domain categorical harmonizer
    // ============================================================

    private List<SuggestedValueDTO> buildClosedDomainCategorical(String unionKey, List<ColRef> sources) {
        Map<String, Map<String, List<SuggestedRefDTO>>> bySource = new LinkedHashMap<>();
        Map<String, Integer> tokenGlobalFreq = new HashMap<>();
        Set<String> uniqueTokens = new LinkedHashSet<>();

        for (ColRef src : sources) {
            String srcKey = src.fileKey() + "::" + StringUtil.safe(src.column);
            Map<String, List<SuggestedRefDTO>> tokenToRefs = bySource.computeIfAbsent(srcKey, k -> new LinkedHashMap<>());

            List<String> rawVals = StringUtil.safeList(src.rawValues);
            int used = 0;
            for (String rv : rawVals) {
                if (used >= MAX_VALUES) break;

                String nv = NormalizationUtil.normalizeValue(rv);
                if (nv.isEmpty()) { used++; continue; }

                uniqueTokens.add(nv);
                tokenGlobalFreq.put(nv, tokenGlobalFreq.getOrDefault(nv, 0) + 1);

                SuggestedRefDTO ref = new SuggestedRefDTO();
                ref.setNodeId(src.nodeId);
                ref.setFileName(src.fileName);
                ref.setGroupColumn(src.column);
                ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                ref.setValue(rv);

                tokenToRefs.computeIfAbsent(nv, k -> new ArrayList<>()).add(ref);
                used++;
            }
        }

        if (uniqueTokens.size() < MIN_DOMAIN_UNIQUE || uniqueTokens.size() > MAX_DOMAIN_UNIQUE) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>(uniqueTokens);
        Map<String, float[]> tokenVec = new HashMap<>();
        for (String t : tokens) tokenVec.put(t, embedSingleValue(t));

        UnionFind uf = new UnionFind(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            for (int j = i + 1; j < tokens.size(); j++) {
                String a = tokens.get(i);
                String b = tokens.get(j);

                if (isObviousAlias(a, b)) {
                    uf.union(i, j);
                    continue;
                }

                double sim = MappingMathUtil.cosine(tokenVec.get(a), tokenVec.get(b));
                if (sim >= THRESH_TOKEN_ALIAS) uf.union(i, j);
            }
        }

        Map<Integer, List<String>> compToTokens = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            int root = uf.find(i);
            compToTokens.computeIfAbsent(root, k -> new ArrayList<>()).add(tokens.get(i));
        }

        Map<String, List<SuggestedRefDTO>> canonToRefs = new LinkedHashMap<>();
        Map<String, Integer> canonSupport = new HashMap<>();

        for (List<String> comp : compToTokens.values()) {
            String canon = pickCanonicalToken(comp, tokenGlobalFreq);

            List<SuggestedRefDTO> refs = new ArrayList<>();
            int support = 0;

            for (Map<String, List<SuggestedRefDTO>> tokenToRefs : bySource.values()) {
                for (String t : comp) {
                    List<SuggestedRefDTO> r = tokenToRefs.get(t);
                    if (r != null && !r.isEmpty()) {
                        refs.addAll(r);
                        support += r.size();
                    }
                }
            }

            if (!refs.isEmpty()) {
                canonToRefs.put(canon, refs);
                canonSupport.put(canon, support);
            }
        }

        if (!joinsAcrossAtLeastTwoFiles(canonToRefs)) return Collections.emptyList();

        List<Map.Entry<String, List<SuggestedRefDTO>>> entries = new ArrayList<>(canonToRefs.entrySet());
        entries.removeIf(x -> canonSupport.getOrDefault(x.getKey(), 0) < MIN_SUPPORT_FOR_CANON);
        if (entries.size() < 2) return Collections.emptyList();

        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        List<SuggestedValueDTO> out = new ArrayList<>();
        for (Map.Entry<String, List<SuggestedRefDTO>> x : entries) {
            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(x.getKey());
            
            // Add terminology and description for the value
            populateValueTerminologyAndDescription(vd, x.getKey(), unionKey, getAllValuesForContext(sources));
            
            vd.setMapping(x.getValue());
            out.add(vd);
        }
        return out;
    }
    
    private List<String> getAllValuesForContext(List<ColRef> sources) {
        List<String> allValues = new ArrayList<>();
        for (ColRef src : sources) {
            if (src.rawValues != null) {
                for (String v : src.rawValues) {
                    if (v != null && !v.trim().isEmpty()) {
                        allValues.add(v.trim());
                    }
                }
            }
        }
        return allValues;
    }
    
    private void populateValueTerminologyAndDescription(SuggestedValueDTO value, String valueName, String columnContext, List<String> allValues) {
        // Try to find SNOMED CT terminology for the value using RDFService
        List<OntologyTermDTO> results = rdfService.getSNOMEDTermSuggestions(valueName);
        
        if (!results.isEmpty()) {
            OntologyTermDTO best = results.get(0);
            // Extract SNOMED code from IRI (e.g., http://snomed.info/sct/12345 -> 12345)
            String conceptId = best.getIri().replaceAll(".*sct/", "");
            value.setTerminology(conceptId);
            // Use terminology to enhance description
            value.setDescription(descriptionGenerator.generateValueDescription(valueName, columnContext, allValues));
        } else {
            value.setTerminology("");
            value.setDescription(descriptionGenerator.generateValueDescription(valueName, columnContext, allValues));
        }
    }

    private boolean joinsAcrossAtLeastTwoFiles(Map<String, List<SuggestedRefDTO>> canonToRefs) {
        Set<String> fileKeys = new HashSet<>();
        for (List<SuggestedRefDTO> refs : canonToRefs.values()) {
            for (SuggestedRefDTO r : refs) {
                fileKeys.add(StringUtil.safe(r.getNodeId()) + "::" + StringUtil.safe(r.getFileName()));
                if (fileKeys.size() >= 2) return true;
            }
        }
        return false;
    }

    private boolean isObviousAlias(String a, String b) {
        String x = StringUtil.safe(a).trim();
        String y = StringUtil.safe(b).trim();
        if (x.isEmpty() || y.isEmpty()) return false;

        // single-letter vs word starting with same letter
        if (x.length() == 1 && y.length() >= 3) return y.charAt(0) == x.charAt(0);
        if (y.length() == 1 && x.length() >= 3) return x.charAt(0) == y.charAt(0);

        // unknown variants
        if ((x.equals("unk") && y.startsWith("unk")) || (y.equals("unk") && x.startsWith("unk"))) return true;
        return false;
    }

    private String pickCanonicalToken(List<String> tokens, Map<String, Integer> globalFreq) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;

        for (String t : tokens) {
            int freq = globalFreq.getOrDefault(t, 0);
            int len = t.length();

            int score = freq * 100 + Math.min(len, 30);
            if (len == 1) score -= 500;

            if (best == null || score > bestScore) {
                best = t;
                bestScore = score;
            } else if (score == bestScore && best != null) {
                if (t.compareToIgnoreCase(best) < 0) best = t;
            }
        }

        return best == null ? tokens.get(0) : best;
    }

    private static final class UnionFind {
        final int[] parent;
        final int[] rank;

        UnionFind(int n) {
            parent = new int[n];
            rank = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        int find(int x) {
            while (parent[x] != x) {
                parent[x] = parent[parent[x]];
                x = parent[x];
            }
            return x;
        }

        void union(int a, int b) {
            int ra = find(a);
            int rb = find(b);
            if (ra == rb) return;
            if (rank[ra] < rank[rb]) parent[ra] = rb;
            else if (rank[ra] > rank[rb]) parent[rb] = ra;
            else { parent[rb] = ra; rank[ra]++; }
        }
    }

    // ============================================================
    // Mapping builders
    // ============================================================

    private SuggestedMappingDTO buildMappingSkeleton(String mappingType, String fileName, List<ColRef> pickedCols) {
        SuggestedMappingDTO mapping = new SuggestedMappingDTO();
        mapping.setMappingType(mappingType);
        mapping.setFileName(fileName);

        mapping.setNodeId("");
        
        // Add terminology and description for the mapping
        String representativeName = pickedCols.isEmpty() ? "" : pickedCols.get(0).concept;
        populateMappingTerminologyAndDescription(mapping, representativeName, pickedCols);

        mapping.setColumns(extractSourceColumnNames(pickedCols));
        return mapping;
    }
    
    private void populateMappingTerminologyAndDescription(SuggestedMappingDTO mapping, String conceptName, List<ColRef> cols) {
        // Try to find SNOMED CT terminology for the concept using RDFService
        List<OntologyTermDTO> results = rdfService.getSNOMEDTermSuggestions(conceptName);
        
        if (!results.isEmpty()) {
            OntologyTermDTO best = results.get(0);
            // Extract SNOMED code from IRI (e.g., http://snomed.info/sct/12345 -> 12345)
            String conceptId = best.getIri().replaceAll(".*sct/", "");
            mapping.setTerminology(conceptId);
            mapping.setDescription(descriptionGenerator.generateColumnDescription(conceptName, best.getLabel()));
        } else {
            mapping.setTerminology("");
            mapping.setDescription(descriptionGenerator.generateColumnDescription(conceptName, null));
        }
    }

    private List<String> extractSourceColumnNames(List<ColRef> cols) {
        List<String> out = new ArrayList<>();
        for (ColRef c : cols) out.add(c.column);
        return out;
    }

    // ============================================================
    // Range value (single "numeric" or "date" bucket)
    // ============================================================

    private List<SuggestedValueDTO> buildRangeValue(String unionName, String type, List<ColRef> sources) {
        SuggestedValueDTO v = new SuggestedValueDTO();
        v.setName(type);
        
        // Add terminology and description for range values
        populateValueTerminologyAndDescription(v, type, unionName, getAllValuesForContext(sources));
        
        v.setMapping(new ArrayList<>());

        for (ColRef src : sources) {
            SuggestedRefDTO ref = new SuggestedRefDTO();
            ref.setNodeId(src.nodeId);
            ref.setFileName(src.fileName);
            ref.setGroupColumn(src.column);
            ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
            ref.setValue(type);
            v.getMapping().add(ref);
        }

        return Collections.singletonList(v);
    }

    private String rangeDescriptionFromSources(String type, List<ColRef> sources) {
        if (sources == null || sources.isEmpty()) return "";

        List<String> parts = new ArrayList<>();
        for (ColRef s : sources) {
            Double min = s.stats.numMin;
            Double max = s.stats.numMax;
            if (min != null && max != null) {
                parts.add(s.fileName + ":" + s.column + "=[" + StringUtil.stripTrailingZeros(min) + "," + StringUtil.stripTrailingZeros(max) + "]");
            } else {
                parts.add(s.fileName + ":" + s.column + "=[unknown]");
            }
        }
        return String.join(" | ", parts);
    }

    // ============================================================
    // Enum mapping (schema provided)
    // ============================================================

    private List<SuggestedValueDTO> buildEnumValueMappings(SchemaFieldRef field, List<ColRef> sources) {
        List<EnumRef> enums = new ArrayList<>();
        for (String ev : field.enumValues) {
            float[] vec = embedSingleValue(ev);
            enums.add(new EnumRef(ev, vec));
        }

        Map<String, List<SuggestedRefDTO>> bucket = new LinkedHashMap<>();
        for (EnumRef er : enums) bucket.put(er.value, new ArrayList<>());

        for (ColRef src : sources) {
            List<String> rawVals = StringUtil.safeList(src.rawValues);

            int used = 0;
            for (String rv : rawVals) {
                if (used >= MAX_VALUES) break;

                String nv = NormalizationUtil.normalizeValue(rv);
                if (nv.isEmpty()) { used++; continue; }

                float[] vvec = embedSingleValue(nv);

                EnumRef best = null;
                double bestSim = -1;
                for (EnumRef er : enums) {
                    double sim = MappingMathUtil.cosine(vvec, er.vec);
                    if (sim > bestSim) { bestSim = sim; best = er; }
                }

                if (best != null && bestSim >= THRESH_ENUM_VALUE) {
                    SuggestedRefDTO ref = new SuggestedRefDTO();
                    ref.setNodeId(src.nodeId);
                    ref.setFileName(src.fileName);
                    ref.setGroupColumn(src.column);
                    ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                    ref.setValue(rv);
                    bucket.get(best.value).add(ref);
                }

                used++;
            }
        }

        List<SuggestedValueDTO> out = new ArrayList<>();
        for (Map.Entry<String, List<SuggestedRefDTO>> e : bucket.entrySet()) {
            if (e.getValue().isEmpty()) continue;

            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(e.getKey());
            
            // Add terminology and description for enum value
            populateValueTerminologyAndDescription(vd, e.getKey(), 
                field.name, getAllValuesForContext(sources));
            
            vd.setMapping(e.getValue());
            out.add(vd);
        }

        return out;
    }

    // ============================================================
    // Categorical clustering fallback
    // ============================================================

    private List<SuggestedValueDTO> buildClusteredValueMappings(String unionName, List<ColRef> sources) {
        List<ValueItem> items = new ArrayList<>();
        Map<String, Integer> freq = new HashMap<>();

        for (ColRef src : sources) {
            List<String> rawVals = StringUtil.safeList(src.rawValues);
            int used = 0;

            for (String rv : rawVals) {
                if (items.size() >= MAX_VALUES_PER_UNION) break;
                if (used >= MAX_VALUES) break;

                String nv = NormalizationUtil.normalizeValue(rv);
                if (nv.isEmpty()) { used++; continue; }

                freq.put(nv, freq.getOrDefault(nv, 0) + 1);

                SuggestedRefDTO ref = new SuggestedRefDTO();
                ref.setNodeId(src.nodeId);
                ref.setFileName(src.fileName);
                ref.setGroupColumn(src.column);
                ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                ref.setValue(rv);

                items.add(new ValueItem(nv, embedSingleValue(nv), ref));
                used++;
            }
        }

        if (items.isEmpty()) return Collections.emptyList();

        List<Cluster> clusters = new ArrayList<>();
        for (ValueItem it : items) {
            Cluster best = null;
            double bestSim = -1;

            for (Cluster c : clusters) {
                double sim = MappingMathUtil.cosine(it.vec, c.centroid);
                if (sim > bestSim) { bestSim = sim; best = c; }
            }

            if (best != null && bestSim >= THRESH_CLUSTER) best.add(it);
            else { Cluster c = new Cluster(); c.add(it); clusters.add(c); }
        }

        List<SuggestedValueDTO> out = new ArrayList<>();
        for (Cluster c : clusters) {
            if (c.refs.isEmpty()) continue;

            String rep = pickRepresentative(c, freq);

            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(rep);
            
            // Add terminology and description for clustered value
            populateValueTerminologyAndDescription(vd, rep, unionName, getAllValuesForContext(sources));
            
            vd.setMapping(new ArrayList<>(c.refs));
            out.add(vd);
        }

        out.sort((a, b) -> Integer.compare(
                b.getMapping() == null ? 0 : b.getMapping().size(),
                a.getMapping() == null ? 0 : a.getMapping().size()
        ));

        return out;
    }

    private String pickRepresentative(Cluster c, Map<String, Integer> freq) {
        String best = null;
        int bestFreq = -1;

        for (String nv : c.normalizedValues) {
            int f = freq.getOrDefault(nv, 0);
            if (f > bestFreq) { best = nv; bestFreq = f; }
            else if (f == bestFreq && best != null) {
                if (nv.length() < best.length()) best = nv;
            }
        }
        return best == null ? c.normalizedValues.iterator().next() : best;
    }

    // ============================================================
    // Type detection (numeric merging)
    // ============================================================

    private String detectTypeFromSourcesOrSchema(List<ColRef> sources, String schemaType) {
        String st = StringUtil.safe(schemaType).trim().toLowerCase(Locale.ROOT);

        if ("integer".equals(st)) return "integer";
        if ("number".equals(st) || "double".equals(st) || "float".equals(st)) return "double";
        if ("date".equals(st) || "datetime".equals(st)) return "date";

        boolean anyInt = false, anyDouble = false, anyDate = false;
        for (ColRef s : sources) {
            if (s.stats.hasIntegerMarker) anyInt = true;
            if (s.stats.hasDoubleMarker) anyDouble = true;
            if (s.stats.hasDateMarker) anyDate = true;
        }

        if (anyDate) return "date";
        // FIX: if any double present, treat as double (still merge with integers as numeric)
        if (anyDouble) return "double";
        if (anyInt) return "integer";
        return "";
    }

    private List<ColRef> topCols(List<ColScore> cs, int n) {
        List<ColRef> out = new ArrayList<>();
        int lim = Math.min(n, cs.size());
        for (int i = 0; i < lim; i++) out.add(cs.get(i).col);
        return out;
    }

    // ============================================================
    // Stats parsing
    // ============================================================

    private ColStats parseStatsFromValues(List<String> rawValues) {
        ColStats s = new ColStats();
        if (rawValues == null) return s;

        for (String rv : rawValues) {
            String t = StringUtil.safe(rv).trim();
            if (t.isEmpty()) continue;

            String low = t.toLowerCase(Locale.ROOT);

            if ("integer".equals(low)) s.hasIntegerMarker = true;
            if ("double".equals(low)) s.hasDoubleMarker = true;
            if ("date".equals(low)) s.hasDateMarker = true;

            if (low.startsWith("min:")) {
                Double v = tryParseDouble(low.substring("min:".length()));
                if (v != null) s.numMin = v;
            } else if (low.startsWith("max:")) {
                Double v = tryParseDouble(low.substring("max:".length()));
                if (v != null) s.numMax = v;
            } else if (low.startsWith("earliest:")) {
                Long ms = tryParseDateMs(low.substring("earliest:".length()));
                if (ms != null) s.dateMinMs = ms;
            } else if (low.startsWith("latest:")) {
                Long ms = tryParseDateMs(low.substring("latest:".length()));
                if (ms != null) s.dateMaxMs = ms;
            } else if (low.startsWith("step:")) {
                Integer step = tryParseInt(low.substring("step:".length()));
                if (step != null && step > 0) s.stepHint = step;
            }
        }

        return s;
    }

    private Integer tryParseInt(String s) {
        try {
            String x = StringUtil.safe(s).trim();
            if (x.isEmpty()) return null;
            return Integer.parseInt(x);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Double tryParseDouble(String s) {
        try {
            String x = StringUtil.safe(s).trim();
            if (x.isEmpty()) return null;
            return Double.parseDouble(x);
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long tryParseDateMs(String s) {
        try {
            String x = StringUtil.safe(s).trim();
            if (x.isEmpty()) return null;
            return javax.xml.bind.DatatypeConverter.parseDateTime(x).getTimeInMillis();
        } catch (Exception ignore) {
            try {
                String x = StringUtil.safe(s).trim();
                if (x.isEmpty()) return null;
                return new Date(Date.parse(x)).getTime();
            } catch (Exception ignore2) {
                return null;
            }
        }
    }

    // ============================================================
    // Schema parsing
    // ============================================================

    private List<SchemaFieldRef> parseSchemaFields(String schemaJson) {
        List<JsonSchemaParsingUtil.SchemaFieldDef> defs =
                JsonSchemaParsingUtil.parseSchemaProperties(objectMapper, schemaJson, MAX_ENUM);

        if (defs.isEmpty()) return Collections.emptyList();

        List<SchemaFieldRef> out = new ArrayList<>();
        for (JsonSchemaParsingUtil.SchemaFieldDef d : defs) {
            String fieldName = d.getName();
            String type = d.getType();
            List<String> enumVals = d.getEnumValues();

            // Use rich combined embedding for schema fields too
            float[] combined = embedSchemaField(fieldName, type, enumVals);

            out.add(new SchemaFieldRef(fieldName, type, enumVals, combined));
        }
        return out;
    }

    /**
     * Create rich embedding for schema field with name, type, and enum values
     */
    private float[] embedSchemaField(String fieldName, String type, List<String> enumVals) {
        StringBuilder prompt = new StringBuilder();
        
        if (fieldName != null && !fieldName.trim().isEmpty()) {
            prompt.append(fieldName);
        }
        
        if (type != null && !type.trim().isEmpty()) {
            if (prompt.length() > 0) prompt.append(" (").append(type).append(")");
            else prompt.append(type);
        }
        
        if (enumVals != null && !enumVals.isEmpty()) {
            StringBuilder valueStr = new StringBuilder();
            int count = 0;
            
            for (String val : enumVals) {
                if (count >= MAX_VALUES_FOR_EMBEDDING) break;
                if (val == null) continue;
                
                String s = NormalizationUtil.normalizeValue(val);
                if (s.isEmpty()) { count++; continue; }
                
                if (valueStr.length() > 0) valueStr.append(", ");
                valueStr.append(s);
                count++;
            }
            
            if (valueStr.length() > 0) {
                if (prompt.length() > 0) prompt.append(": ");
                prompt.append(valueStr);
            }
        }
        
        return embeddingsClient.embed(prompt.toString());
    }

    // ============================================================
    // Source <-> Schema scoring
    // ============================================================

    private SchemaScore bestSchemaMatch(ColRef src, List<SchemaFieldRef> fields) {
        SchemaFieldRef best = null;
        double bestSim = -1.0;

        for (SchemaFieldRef f : fields) {
            double sim = MappingMathUtil.cosine(src.vec, f.vec);
            if (sim > bestSim) { bestSim = sim; best = f; }
        }

        if (best == null) return null;
        return new SchemaScore(best, bestSim);
    }

    // ============================================================
    // Column clustering
    // ============================================================

    private List<ColCluster> clusterColumns(List<ColRef> all) {
        logger.info("[TRACE] clusterColumns: input {} columns", all.size());
        
        Map<String, ColCluster> byConcept = new LinkedHashMap<>();
        for (ColRef c : all) {
            String key = sanitizeUnionName(StringUtil.safe(c.concept).trim().toLowerCase(Locale.ROOT));
            if (key.isEmpty()) key = "__empty__";
            ColCluster cl = byConcept.get(key);
            if (cl == null) {
                cl = new ColCluster();
                byConcept.put(key, cl);
            }
            cl.add(c);
        }
        
        logger.info("[TRACE] clusterColumns: formed {} initial concept-based clusters", byConcept.size());
        if (byConcept.size() <= 5) {
            logger.info("[TRACE] clusterColumns: initial cluster keys: {}", byConcept.keySet());
        }

        List<ColCluster> clusters = new ArrayList<>(byConcept.values());
        
        // Log first 3 cluster centroids to inspect embedding quality
        for (int i = 0; i < Math.min(3, clusters.size()); i++) {
            ColCluster cl = clusters.get(i);
            float[] centroid = cl.centroid;
            float magnitude = 0;
            for (float v : centroid) magnitude += v * v;
            magnitude = (float) Math.sqrt(magnitude);
            
            logger.info("[TRACE-CENTROID] Cluster {} ({}): centroid[0..5]=[{}, {}, {}, {}, {}, {}], magnitude={}, {} cols",
                i + 1,
                cl.representativeConcept != null ? cl.representativeConcept : "?",
                centroid.length > 0 ? String.format("%.4f", centroid[0]) : "?",
                centroid.length > 1 ? String.format("%.4f", centroid[1]) : "?",
                centroid.length > 2 ? String.format("%.4f", centroid[2]) : "?",
                centroid.length > 3 ? String.format("%.4f", centroid[3]) : "?",
                centroid.length > 4 ? String.format("%.4f", centroid[4]) : "?",
                centroid.length > 5 ? String.format("%.4f", centroid[5]) : "?",
                String.format("%.4f", magnitude),
                cl.cols.size());
        }

        // Step 2: fuzzy merge across those pre-clusters
        List<ColCluster> merged = new ArrayList<>();
        int mergeCount = 0;
        double maxSim = -1;
        double minSim = 1;
        double sumSim = 0;
        int simCount = 0;
        
        for (ColCluster col : clusters) {
            ColCluster best = null;
            double bestSim = -1;

            for (ColCluster cl : merged) {
                double sim = MappingMathUtil.cosine(col.centroid, cl.centroid);
                if (sim > bestSim) { bestSim = sim; best = cl; }
                
                // Track similarity statistics
                if (sim > maxSim) maxSim = sim;
                if (sim < minSim) minSim = sim;
                sumSim += sim;
                simCount++;
            }

            if (best != null && bestSim >= THRESH_COL_CLUSTER) {
                for (ColRef r : col.cols) best.add(r);
                mergeCount++;
                
                // Log first few merges to see what's happening
                if (mergeCount <= 5) {
                    logger.info("[TRACE-MERGE] Merging cluster {} (concept='{}') into existing cluster (similarity={})",
                        mergeCount, col.representativeConcept, String.format("%.4f", bestSim));
                }
            } else {
                merged.add(col);
            }
        }
        
        double avgSim = simCount > 0 ? sumSim / simCount : 0;
        logger.info("[TRACE] clusterColumns: merged {} clusters (threshold={}), final count: {}", 
            mergeCount, THRESH_COL_CLUSTER, merged.size());
        logger.info("[TRACE] clusterColumns: similarity stats - min={}, max={}, avg={}, comparisons={}",
            String.format("%.4f", minSim), String.format("%.4f", maxSim), 
            String.format("%.4f", avgSim), simCount);

        for (ColCluster c : merged) {
            c.representativeConcept = pickClusterRepresentativeConcept(c.cols);
        }

        return merged;
    }

    private String pickClusterRepresentativeConcept(List<ColRef> cols) {
        String best = null;
        int bestLen = Integer.MAX_VALUE;

        for (ColRef c : cols) {
            String name = StringUtil.safe(c.concept).trim();
            if (name.isEmpty()) continue;

            int len = name.length();
            if (len < bestLen) { best = name; bestLen = len; }
            else if (len == bestLen && best != null) {
                if (name.compareToIgnoreCase(best) < 0) best = name;
            }
        }

        if (best == null) best = cols.get(0).concept;
        return best;
    }

    // ============================================================
    // Request-learned canonicalization (no hardcoded tokens)
    // ============================================================

    private static final class RawColName {
        final String raw;
        final List<String> tokens;

        RawColName(String raw, List<String> tokens) {
            this.raw = raw;
            this.tokens = tokens;
        }
    }

    private static final class LearnedNoise {
        final Set<String> noiseTokens;
        final Set<String> suffixNoiseTokens;
        final Map<String, Integer> df;
        final int nCols;

        LearnedNoise(Set<String> noiseTokens, Set<String> suffixNoiseTokens, Map<String, Integer> df, int nCols) {
            this.noiseTokens = noiseTokens;
            this.suffixNoiseTokens = suffixNoiseTokens;
            this.df = df;
            this.nCols = nCols;
        }
    }

    private static final class CanonicalName {
        final String concept;
        final double genericness; // higher => more generic
        final int tokenCount;

        CanonicalName(String concept, double genericness, int tokenCount) {
            this.concept = concept;
            this.genericness = genericness;
            this.tokenCount = tokenCount;
        }
    }

    private LearnedNoise learnNoiseFromRequest(List<RawColName> rawNames) {
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> suffixCount = new HashMap<>();

        int n = 0;
        for (RawColName rn : rawNames) {
            if (rn == null || rn.tokens == null || rn.tokens.isEmpty()) continue;
            n++;

            Set<String> uniq = new HashSet<>(rn.tokens);
            for (String t : uniq) {
                if (t == null || t.isEmpty()) continue;
                df.put(t, df.getOrDefault(t, 0) + 1);
            }

            String last = rn.tokens.get(rn.tokens.size() - 1);
            if (last != null && !last.isEmpty()) {
                suffixCount.put(last, suffixCount.getOrDefault(last, 0) + 1);
            }
        }

        Set<String> noise = new HashSet<>();
        Set<String> suffixNoise = new HashSet<>();

        for (Map.Entry<String, Integer> e : df.entrySet()) {
            String t = e.getKey();
            int c = e.getValue();
            if (c >= NOISE_DF_MIN_COUNT) noise.add(t);
            else if (n > 0 && (c / (double) n) >= NOISE_DF_FRACTION) noise.add(t);
        }

        for (Map.Entry<String, Integer> e : suffixCount.entrySet()) {
            if (e.getValue() >= SUFFIX_NOISE_MIN_COUNT) {
                suffixNoise.add(e.getKey());
            }
        }

        // do not mark everything as noise if dataset is tiny
        if (n <= 6 && noise.size() > 0) {
            // keep only the highest-df tokens
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(df.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            Set<String> keep = new HashSet<>();
            for (int i = 0; i < Math.min(3, entries.size()); i++) keep.add(entries.get(i).getKey());
            noise.retainAll(keep);
        }

        return new LearnedNoise(noise, suffixNoise, df, n);
    }

    private List<String> tokenizeName(String rawColumn) {
        String s0 = StringUtil.safe(rawColumn).trim();
        if (s0.isEmpty()) return Collections.emptyList();

        String s = NormalizationUtil.splitCamelStrong(s0);
        s = s.toLowerCase(Locale.ROOT);

        String[] parts = s.split("[^a-z0-9]+");
        List<String> toks = new ArrayList<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;

            // drop pure digits
            if (t.matches("\\d+")) continue;

            // drop embedded digits
            String t2 = t.replaceAll("\\d+", "");
            if (t2.isEmpty()) continue;

            t2 = normalizeToken(t2);
            if (t2.isEmpty()) continue;

            toks.add(t2);
        }

        // drop leading single-letter prefix if next token is informative
        if (toks.size() >= 2 && toks.get(0).length() == 1 && toks.get(1).length() >= 3) {
            toks.remove(0);
        }

        return toks;
    }

    private CanonicalName canonicalConceptName(String rawColumn, LearnedNoise noise) {
        List<String> toks = tokenizeName(rawColumn);
        if (toks.isEmpty()) return new CanonicalName("", 1.0, 0);

        List<String> kept = new ArrayList<>();
        double genericAccum = 0.0;

        for (int i = 0; i < toks.size(); i++) {
            String t = toks.get(i);

            if (noise != null) {
                if (noise.noiseTokens.contains(t)) continue;

                // suffix noise: if token is last, and it is a learned common suffix, drop it
                if (i == toks.size() - 1 && noise.suffixNoiseTokens.contains(t)) continue;

                // genericness accumulates based on DF in this request
                int df = noise.df.getOrDefault(t, 0);
                if (noise.nCols > 0) {
                    double frac = df / (double) noise.nCols;
                    genericAccum += frac;
                }
            }

            kept.add(t);
        }

        // If we removed everything, fall back to non-noise tokens (or original tokens)
        if (kept.isEmpty()) kept = toks;

        String concept = String.join(" ", kept).replaceAll("\\s+", " ").trim();
        int tokenCount = kept.size();

        double genericness = (tokenCount <= 0) ? 1.0 : (genericAccum / tokenCount);
        return new CanonicalName(concept, genericness, tokenCount);
    }

    private String normalizeToken(String t) {
        String x = StringUtil.safe(t).trim().toLowerCase(Locale.ROOT);
        if (x.isEmpty()) return "";

        // very light stemming WITHOUT breaking words like "dress" -> "dres"
        if (x.endsWith("ing") && x.length() > 5) x = x.substring(0, x.length() - 3);
        else if (x.endsWith("ed") && x.length() > 4) x = x.substring(0, x.length() - 2);
        else if (x.endsWith("es") && x.length() > 4) x = x.substring(0, x.length() - 2);
        else if (x.endsWith("s") && x.length() > 4 && !x.endsWith("ss")) x = x.substring(0, x.length() - 1);

        return x;
    }

    private boolean looksLikeInformativeCategorical(ColStats stats, List<String> rawValues) {
        if (stats == null) return false;
        if (stats.hasIntegerMarker || stats.hasDoubleMarker || stats.hasDateMarker) return false;
        if (rawValues == null) return false;

        int nonEmpty = 0;
        Set<String> uniq = new HashSet<>();
        for (String rv : rawValues) {
            String nv = NormalizationUtil.normalizeValue(rv);
            if (nv.isEmpty()) continue;
            nonEmpty++;
            uniq.add(nv);
            if (nonEmpty >= 20) break;
        }
        // small unique domain => likely informative
        return uniq.size() >= 2 && uniq.size() <= 20;
    }

    private String sanitizeUnionName(String raw) {
        String s = StringUtil.safe(raw).trim();
        if (s.isEmpty()) return "";
        s = s.replaceAll("\\s+", "_");
        s = s.replaceAll("[^A-Za-z0-9_]", "");
        if (!s.isEmpty() && Character.isDigit(s.charAt(0))) s = "_" + s;
        return s;
    }

    private String makeUnique(String base, Set<String> used) {
        String b = StringUtil.safe(base).trim();
        if (b.isEmpty()) b = "field";

        String out = b;
        int i = 2;
        while (used.contains(out)) {
            out = b + "_" + i;
            i++;
        }
        used.add(out);
        return out;
    }

    // ============================================================
    // Embeddings (using LLM via EmbeddingsClient)
    // ============================================================
    
    private static final int MAX_VALUES_FOR_EMBEDDING = 15;

    /**
     * Create a single rich embedding that combines column name with its values.
     * This allows the LLM to understand semantic relationships like:
     * - "Type: Ischemic, Hemorrhagic" matching "Etiology (Isch/Hem): Hem, HEM, Isch"
     * - "Bathing: integer, min:1, max:7" matching "BathingBART1: integer, min:0, max:5"
     */
    private float[] embedColumnWithValues(String columnName, List<String> values, ColStats stats) {
        StringBuilder prompt = new StringBuilder();
        
        // Add column name
        if (columnName != null && !columnName.trim().isEmpty()) {
            prompt.append(columnName);
        }
        
        // Add values if informative
        if (values != null && !values.isEmpty()) {
            StringBuilder valueStr = new StringBuilder();
            int count = 0;
            
            for (String raw : values) {
                if (count >= MAX_VALUES_FOR_EMBEDDING) break;
                if (raw == null) continue;
                
                String s = NormalizationUtil.normalizeValue(raw);
                if (s.isEmpty()) { count++; continue; }
                
                if (valueStr.length() > 0) valueStr.append(", ");
                valueStr.append(s);
                count++;
            }
            
            if (valueStr.length() > 0) {
                if (prompt.length() > 0) prompt.append(": ");
                prompt.append(valueStr);
            }
        }
        
        // Embed the rich combined representation
        String promptStr = prompt.toString();
        float[] result = embeddingsClient.embed(promptStr);
        
        // Log first 3 embeddings to inspect vector quality
        int loggedSoFar = embeddingLogCount.getAndIncrement();
        if (loggedSoFar < 3) {
            float magnitude = 0;
            for (float v : result) magnitude += v * v;
            magnitude = (float) Math.sqrt(magnitude);
            
            logger.info("[TRACE-EMBED] Column '{}' -> vector[0..5]=[{}, {}, {}, {}, {}, {}], magnitude={}, dim={}",
                promptStr.length() > 40 ? promptStr.substring(0, 40) + "..." : promptStr,
                result.length > 0 ? String.format("%.4f", result[0]) : "?",
                result.length > 1 ? String.format("%.4f", result[1]) : "?",
                result.length > 2 ? String.format("%.4f", result[2]) : "?",
                result.length > 3 ? String.format("%.4f", result[3]) : "?",
                result.length > 4 ? String.format("%.4f", result[4]) : "?",
                result.length > 5 ? String.format("%.4f", result[5]) : "?",
                String.format("%.4f", magnitude),
                result.length);
        }
        
        return result;
    }

    private float[] embedName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return embeddingsClient.embed("");
        }
        // Just embed the name directly - combination with values happens at higher level
        return embeddingsClient.embed(name);
    }

    private float[] embedValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return embeddingsClient.embed("");
        }

        // Concatenate values as a list so LLM can understand relationships
        // e.g., "Ischemic, Hemorrhagic" or "Hem, HEM, Isch"
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String raw : values) {
            if (count >= MAX_VALUES) break;
            if (raw == null) continue;

            String s = NormalizationUtil.normalizeValue(raw);
            if (s.isEmpty()) { count++; continue; }

            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
            count++;
        }

        // Embed the concatenated list so relationships are preserved
        return embeddingsClient.embed(sb.toString());
    }

    private float[] embedEnum(List<String> enumVals, String typeHint) {
        StringBuilder sb = new StringBuilder();
        
        if (typeHint != null && !typeHint.trim().isEmpty()) {
            sb.append(typeHint).append(": ");
        }

        if (enumVals != null) {
            int count = 0;
            for (String raw : enumVals) {
                if (count >= MAX_ENUM) break;
                if (raw == null) continue;

                String s = NormalizationUtil.normalizeValue(raw);
                if (s.isEmpty()) { count++; continue; }

                if (count > 0) sb.append(", ");
                sb.append(s);
                count++;
            }
        }

        return embeddingsClient.embed(sb.toString());
    }

    private float[] embedSingleValue(String raw) {
        if (raw == null) {
            return embeddingsClient.embed("");
        }
        String s = NormalizationUtil.normalizeValue(raw);
        if (s.isEmpty()) {
            return embeddingsClient.embed("");
        }

        // Use LLM embeddings for better semantic understanding
        return embeddingsClient.embed(s);
    }

    // ============================================================
    // Structs
    // ============================================================

    private static final class ColStats {
        boolean hasIntegerMarker = false;
        boolean hasDoubleMarker = false;
        boolean hasDateMarker = false;

        Double numMin = null;
        Double numMax = null;

        Long dateMinMs = null;
        Long dateMaxMs = null;

        Integer stepHint = null;   // e.g., 5
    }

    private static final class ColRef {
        final String nodeId;
        final String fileName;
        final String column;
        final String concept;
        final List<String> rawValues;
        final float[] vec;
        final ColStats stats;

        ColRef(String nodeId, String fileName, String column, String concept, List<String> rawValues, float[] vec, ColStats stats) {
            this.nodeId = nodeId;
            this.fileName = fileName;
            this.column = column;
            this.concept = (concept == null || concept.trim().isEmpty()) ? StringUtil.safe(column) : concept;
            this.rawValues = rawValues;
            this.vec = vec;
            this.stats = (stats == null) ? new ColStats() : stats;
        }

        String fileKey() { return nodeId + "::" + fileName; }
    }

    private static final class SchemaFieldRef {
        final String name;
        final String type;
        final List<String> enumValues;
        final float[] vec;

        SchemaFieldRef(String name, String type, List<String> enumValues, float[] vec) {
            this.name = name;
            this.type = type;
            this.enumValues = enumValues == null ? Collections.emptyList() : enumValues;
            this.vec = vec;
        }
    }

    private static final class SchemaScore {
        final SchemaFieldRef field;
        final double sim;

        SchemaScore(SchemaFieldRef field, double sim) {
            this.field = field;
            this.sim = sim;
        }
    }

    private static final class ColScore {
        final ColRef col;
        final double sim;

        ColScore(ColRef col, double sim) {
            this.col = col;
            this.sim = sim;
        }
    }

    private static final class EnumRef {
        final String value;
        final float[] vec;

        EnumRef(String value, float[] vec) {
            this.value = value;
            this.vec = vec;
        }
    }

    private static final class ValueItem {
        final String normalized;
        final float[] vec;
        final SuggestedRefDTO ref;

        ValueItem(String normalized, float[] vec, SuggestedRefDTO ref) {
            this.normalized = normalized;
            this.vec = vec;
            this.ref = ref;
        }
    }

    private static final class Cluster {
        final List<SuggestedRefDTO> refs = new ArrayList<>();
        final Set<String> normalizedValues = new LinkedHashSet<>();
        float[] centroid = null;
        int count = 0;

        void add(ValueItem it) {
            refs.add(it.ref);
            normalizedValues.add(it.normalized);

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
    }

    private static final class ColCluster {
        final List<ColRef> cols = new ArrayList<>();
        float[] centroid = null;
        int count = 0;
        String representativeConcept = "";

        void add(ColRef col) {
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
}
