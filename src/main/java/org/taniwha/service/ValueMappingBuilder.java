package org.taniwha.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.taniwha.dto.SuggestedRefDTO;
import org.taniwha.dto.SuggestedValueDTO;
import org.taniwha.model.EmbeddedColumn;
import org.taniwha.model.EmbeddedSchemaField;
import org.taniwha.util.MappingMathUtil;
import org.taniwha.util.NormalizationUtil;
import org.taniwha.util.StringUtil;

import java.util.*;

/**
 * ValueMappingBuilder with diagnostic logging.
 *
 * What this logs (high-signal):
 * - Entry: unionKey, detectedType, #sources, schema enum presence
 * - Decision path: date/range vs numeric ordinal vs enum vs closed-domain vs clustering
 * - For numeric ordinal:
 *   - inferred domains per source (min/max/step/kind/categories)
 *   - canonical domain selection rationale
 *   - per canonical bucket: per source assigned index range + numeric interval
 * - For closed-domain:
 *   - domain size, skip reasons, alias unions, component canonical picks
 * - For clustering:
 *   - #items added, cluster merges, representatives
 *
 * NOTE: keep DEBUG enabled for deep traces.
 */
@Component
public class ValueMappingBuilder {

    private static final Logger log = LoggerFactory.getLogger(ValueMappingBuilder.class);

    private final EmbeddingService embeddingService;

    private static final int MAX_VALUES = 80;
    private static final double THRESH_ENUM_VALUE = 0.40;
    private static final double THRESH_CLUSTER = 0.55;
    private static final double THRESH_TOKEN_ALIAS = 0.72;

    // Caps / guards
    private static final int MAX_VALUES_PER_UNION = 220;
    private static final int MAX_ORDINAL_CATEGORIES = 60;

    private static final int MAX_DOMAIN_UNIQUE = 25;
    private static final int MIN_DOMAIN_UNIQUE = 2;

    private static final int MIN_SUPPORT_FOR_CANON = 2;

    private static final int MIN_LEN_FOR_EMBED_ALIAS = 3;

    // Logging guards
    private static final int LOG_MAX_SOURCES_DETAIL = 16;
    private static final int LOG_MAX_VALUES_SAMPLE = 12;
    private static final int LOG_MAX_CLUSTERS_DETAIL = 24;

    public ValueMappingBuilder(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public List<SuggestedValueDTO> buildValuesForConcept(
            String unionKey,
            String detectedType,
            EmbeddedSchemaField schemaFieldOrNull,
            List<EmbeddedColumn> picked
    ) {
        final String uk = StringUtil.safe(unionKey).trim();
        final String dt = StringUtil.safe(detectedType).toLowerCase(Locale.ROOT).trim();

        boolean hasSchemaEnum =
                schemaFieldOrNull != null
                        && schemaFieldOrNull.enumValues != null
                        && !schemaFieldOrNull.enumValues.isEmpty();

        if (log.isInfoEnabled()) {
            log.info("[VMB] buildValuesForConcept: unionKey='{}' detectedType='{}' sources={} schemaEnum={}",
                    uk, dt, picked == null ? 0 : picked.size(), hasSchemaEnum);
        }

        if (log.isDebugEnabled()) {
            debugPickedSources("entry", uk, dt, picked);
            if (hasSchemaEnum) {
                int n = schemaFieldOrNull.enumValues.size();
                List<String> sample = schemaFieldOrNull.enumValues.subList(0, Math.min(n, LOG_MAX_VALUES_SAMPLE));
                log.debug("[VMB] schema enum values (n={} sample={}): {}", n, sample.size(), sample);
            }
        }

        if ("date".equals(dt)) {
            log.info("[VMB] decision: detectedType=date -> buildRangeValue(date)");
            return buildRangeValue(uk, "date", picked);
        }

        if ("integer".equals(dt) || "double".equals(dt)) {
            // Only meaningful for integer scales; for double we fall back to range.
            log.info("[VMB] decision: detectedType numeric ('{}') -> attempt ordinal crosswalk (integer-only), else range", dt);

            List<SuggestedValueDTO> ordinal = buildOrdinalNumericCrosswalkAsNonOverlappingRanges(uk, dt, picked);
            if (!ordinal.isEmpty()) {
                log.info("[VMB] decision: ordinal crosswalk SUCCESS -> emitted {} buckets", ordinal.size());
                return ordinal;
            }
            log.info("[VMB] decision: ordinal crosswalk not applicable/empty -> buildRangeValue({})", dt);
            return buildRangeValue(uk, dt, picked);
        }

        // If schema provides enum, prefer enum mapping first.
        if (hasSchemaEnum) {
            log.info("[VMB] decision: schema enum present -> attempt enum value mapping");
            List<SuggestedValueDTO> ev = buildEnumValueMappings(schemaFieldOrNull, picked);
            if (!ev.isEmpty()) {
                log.info("[VMB] decision: enum mapping SUCCESS -> emitted {} enum buckets", ev.size());
                return ev;
            }
            log.info("[VMB] decision: enum mapping produced 0 -> continue");
        }

        // Attempt closed-domain harmonization (small domains only).
        log.info("[VMB] decision: attempt closed-domain categorical harmonization");
        List<SuggestedValueDTO> harmonized = buildClosedDomainCategorical(uk, picked);
        if (!harmonized.isEmpty()) {
            log.info("[VMB] decision: closed-domain harmonization SUCCESS -> emitted {} buckets", harmonized.size());
            return harmonized;
        }
        log.info("[VMB] decision: closed-domain harmonization empty -> fallback to clustered value mappings");

        // Fallback: clustered value mappings.
        List<SuggestedValueDTO> clustered = buildClusteredValueMappings(uk, picked);
        log.info("[VMB] decision: clustering emitted {} buckets", clustered.size());
        return clustered;
    }

    // ============================================================
    // Range value (single "numeric" or "date" bucket)
    // ============================================================

    private List<SuggestedValueDTO> buildRangeValue(String unionName, String type, List<EmbeddedColumn> sources) {
        SuggestedValueDTO v = new SuggestedValueDTO();
        v.setName(type);
        v.setMapping(new ArrayList<>());

        if (log.isDebugEnabled()) {
            log.debug("[VMB] buildRangeValue: union='{}' type='{}' sources={}",
                    unionName, type, sources == null ? 0 : sources.size());
        }

        if (sources != null) {
            for (EmbeddedColumn src : sources) {
                SuggestedRefDTO ref = new SuggestedRefDTO();
                ref.setNodeId(src.nodeId);
                ref.setFileName(src.fileName);
                ref.setGroupColumn(src.column);
                ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                ref.setValue(type);
                v.getMapping().add(ref);

                if (log.isTraceEnabled()) {
                    log.trace("[VMB] buildRangeValue: +ref {}::{}::{} value={}",
                            src.nodeId, src.fileName, src.column, type);
                }
            }
        }

        return Collections.singletonList(v);
    }

    // ============================================================
    // Numeric ordinal crosswalk (NON-OVERLAPPING, FULL-COVERAGE integer intervals)
    // ============================================================

    private List<SuggestedValueDTO> buildOrdinalNumericCrosswalkAsNonOverlappingRanges(
            String unionKey,
            String detectedType,
            List<EmbeddedColumn> sources
    ) {
        if (!"integer".equalsIgnoreCase(StringUtil.safe(detectedType))) {
            log.debug("[VMB] ordinalCrosswalk: skip (detectedType='{}' != integer)", detectedType);
            return Collections.emptyList();
        }
        if (sources == null || sources.isEmpty()) {
            log.debug("[VMB] ordinalCrosswalk: skip (no sources)");
            return Collections.emptyList();
        }

        List<ScaleDomain> domains = new ArrayList<>();
        Map<String, ScaleDomain> domBySource = new LinkedHashMap<>();

        for (EmbeddedColumn s : sources) {
            ScaleDomain d = inferScaleDomain(s);
            if (d != null) {
                domains.add(d);
                domBySource.put(sourceKey(s), d);
            } else {
                domBySource.put(sourceKey(s), null);
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("[VMB] ordinalCrosswalk: inferred domains count={} (need >=2). union='{}'", domains.size(), unionKey);
            debugDomains(domBySource);
        }

        if (domains.size() < 2) {
            log.info("[VMB] ordinalCrosswalk: not enough inferred scale domains ({} < 2) -> empty", domains.size());
            return Collections.emptyList();
        }

        ScaleDomain canon = pickCanonicalScaleDomainPreferFewer(domains);
        if (canon == null) {
            log.info("[VMB] ordinalCrosswalk: canonical domain null -> empty");
            return Collections.emptyList();
        }

        if (canon.categories.size() < 2 || canon.categories.size() > MAX_ORDINAL_CATEGORIES) {
            log.info("[VMB] ordinalCrosswalk: canonical categories out of range: {} (min=2 max={}) -> empty",
                    canon.categories.size(), MAX_ORDINAL_CATEGORIES);
            return Collections.emptyList();
        }

        if (log.isInfoEnabled()) {
            log.info("[VMB] ordinalCrosswalk: canonical selected kind={} cats={} (min={} max={} step~={})",
                    canon.kind, canon.categories.size(),
                    canon.categories.get(0),
                    canon.categories.get(canon.categories.size() - 1),
                    canon.categories.size() >= 2 ? (canon.categories.get(1) - canon.categories.get(0)) : 0
            );
        }

        List<SuggestedValueDTO> out = new ArrayList<>();
        int nCanon = canon.categories.size();

        for (int i = 0; i < nCanon; i++) {
            int canonCat = canon.categories.get(i);

            List<SuggestedRefDTO> refs = new ArrayList<>();
            for (EmbeddedColumn src : sources) {
                ScaleDomain srcDom = inferScaleDomain(src);
                if (srcDom == null) continue;
                if (srcDom.categories.size() < 2) continue;

                RangeIdx r = mapCanonBucketToSourceIndexRangeByPartition(i, nCanon, srcDom.categories.size());

                int loIdx = MappingMathUtil.clamp(r.lo, 0, srcDom.categories.size() - 1);
                int hiIdx = MappingMathUtil.clamp(r.hi, 0, srcDom.categories.size() - 1);
                if (hiIdx < loIdx) {
                    int tmp = loIdx; loIdx = hiIdx; hiIdx = tmp;
                }

                Map<String, Object> interval = idxRangeToNumericInterval(srcDom.categories, loIdx, hiIdx);

                SuggestedRefDTO ref = new SuggestedRefDTO();
                ref.setNodeId(src.nodeId);
                ref.setFileName(src.fileName);
                ref.setGroupColumn(src.column);
                ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                ref.setValue(interval);
                refs.add(ref);

                if (log.isDebugEnabled()) {
                    log.debug("[VMB] ordinalCrosswalk: canonBucket i={}/{} name='{}' -> src={} srcKind={} srcCats={} idx=[{},{}] interval={}",
                            i, (nCanon - 1), canonCat,
                            sourceKey(src),
                            srcDom.kind,
                            srcDom.categories.size(),
                            loIdx, hiIdx,
                            interval
                    );
                }
            }

            if (refs.isEmpty()) continue;

            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(String.valueOf(canonCat));
            vd.setMapping(refs);
            out.add(vd);
        }

        out.sort(Comparator.comparingInt(a -> MappingMathUtil.safeInt(a.getName(), Integer.MIN_VALUE)));

        if (log.isInfoEnabled()) {
            log.info("[VMB] ordinalCrosswalk: produced {} buckets for union='{}'", out.size(), unionKey);
        }

        return out;
    }

    private RangeIdx mapCanonBucketToSourceIndexRangeByPartition(int canonIndex, int canonSize, int srcSize) {
        if (canonSize <= 1 || srcSize <= 1) return new RangeIdx(0, 0);

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
        RangeIdx(int lo, int hi) { this.lo = lo; this.hi = hi; }
    }

    private Map<String, Object> makeRangeValue(double minValue, double maxValue, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minValue", minValue);
        m.put("maxValue", maxValue);
        m.put("type", type);
        return m;
    }

    /**
     * This is a common root cause for “wrong toileting”:
     * any integer column with min=0 and max in {5,10,15} becomes BARTHEL_ITEM, regardless of what the concept is.
     *
     * Logging here will reveal when "Toileting" (FIM 1..7) or another scale is being interpreted as 0..10 etc.
     */
    private ScaleDomain inferScaleDomain(EmbeddedColumn src) {
        if (src == null || src.stats == null) return null;
        if (!src.stats.hasIntegerMarker) return null;
        if (src.stats.numMin == null || src.stats.numMax == null) return null;

        int min = (int) Math.round(src.stats.numMin);
        int max = (int) Math.round(src.stats.numMax);
        if (max < min) return null;

        Integer hintStep = src.stats.stepHint;

        // FIM: 1..7 step 1
        if (min == 1 && max == 7) {
            return ScaleDomain.linear(min, max, 1, ScaleKind.FIM);
        }

        // Barthel item: 0..5/10/15 step 5
        if (min == 0 && (max == 5 || max == 10 || max == 15)) {
            return ScaleDomain.linear(0, max, 5, ScaleKind.BARTHEL_ITEM);
        }

        // Barthel total: 0..100 step (hint or 5)
        if (min == 0 && max == 100) {
            int step = (hintStep != null && hintStep > 0) ? hintStep : 5;
            return ScaleDomain.linear(0, 100, step, ScaleKind.BARTHEL_TOTAL);
        }

        // Generic small integer span
        int span = max - min;
        if (span >= 1 && span <= 20) {
            return ScaleDomain.linear(min, max, 1, ScaleKind.GENERIC);
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
            if (best == null) { best = d; continue; }

            int dc = d.categories.size();
            int bc = best.categories.size();

            // Prefer fewer categories (as in your original code)
            if (dc < bc) best = d;
            else if (dc == bc) {
                if (kindRank(d.kind) > kindRank(best.kind)) best = d;
            }
        }

        if (log.isDebugEnabled() && best != null) {
            log.debug("[VMB] pickCanonicalScaleDomainPreferFewer: picked kind={} cats={}", best.kind, best.categories.size());
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

        private ScaleDomain(ScaleKind kind, List<Integer> categories) {
            this.kind = kind;
            this.categories = categories;
        }

        static ScaleDomain linear(int min, int max, int step, ScaleKind kind) {
            List<Integer> cats = new ArrayList<>();
            int st = Math.max(step, 1);
            for (int v = min; v <= max; v += st) cats.add(v);
            return new ScaleDomain(kind, cats);
        }
    }

    // ============================================================
    // Enum mapping (schema provided)
    // ============================================================

    private List<SuggestedValueDTO> buildEnumValueMappings(EmbeddedSchemaField field, List<EmbeddedColumn> sources) {
        // Cache embeddings locally to avoid repeated network calls if EmbeddingsClient is remote.
        Map<String, float[]> vecCache = new HashMap<>();

        List<EnumRef> enums = new ArrayList<>();
        for (String ev : field.enumValues) {
            String nev = NormalizationUtil.normalizeValue(ev);
            float[] vec = vecCache.computeIfAbsent(nev, k -> embeddingService.embedSingleValue(k));
            enums.add(new EnumRef(ev, vec));
        }

        if (log.isDebugEnabled()) {
            log.debug("[VMB] enumMapping: enumCount={} sources={}", enums.size(), sources == null ? 0 : sources.size());
        }

        Map<String, List<SuggestedRefDTO>> bucket = new LinkedHashMap<>();
        for (EnumRef er : enums) bucket.put(er.value, new ArrayList<>());

        int totalConsidered = 0;
        int totalMapped = 0;

        for (EmbeddedColumn src : sources) {
            List<String> rawVals = StringUtil.safeList(src.rawValues);

            int used = 0;
            for (String rv : rawVals) {
                if (used >= MAX_VALUES) break;

                String nv = NormalizationUtil.normalizeValue(rv);
                if (nv.isEmpty()) { used++; continue; }

                float[] vvec = vecCache.computeIfAbsent(nv, k -> embeddingService.embedSingleValue(k));

                EnumRef best = null;
                double bestSim = -1;
                for (EnumRef er : enums) {
                    double sim = MappingMathUtil.cosine(vvec, er.vec);
                    if (sim > bestSim) { bestSim = sim; best = er; }
                }

                totalConsidered++;

                if (best != null && bestSim >= THRESH_ENUM_VALUE) {
                    SuggestedRefDTO ref = new SuggestedRefDTO();
                    ref.setNodeId(src.nodeId);
                    ref.setFileName(src.fileName);
                    ref.setGroupColumn(src.column);
                    ref.setGroupKey(StringUtil.groupKey(src.nodeId, src.fileName, src.column));
                    ref.setValue(rv);
                    bucket.get(best.value).add(ref);
                    totalMapped++;

                    if (log.isDebugEnabled()) {
                        log.debug("[VMB] enumMapping: src={} rv='{}' -> enum='{}' sim={}",
                                sourceKey(src), rv, best.value, round3(bestSim));
                    }
                } else {
                    if (log.isTraceEnabled()) {
                        log.trace("[VMB] enumMapping: src={} rv='{}' -> no match (bestSim={})",
                                sourceKey(src), rv, round3(bestSim));
                    }
                }

                used++;
            }
        }

        List<SuggestedValueDTO> out = new ArrayList<>();
        for (Map.Entry<String, List<SuggestedRefDTO>> e : bucket.entrySet()) {
            if (e.getValue().isEmpty()) continue;

            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(e.getKey());
            vd.setMapping(e.getValue());
            out.add(vd);
        }

        if (log.isInfoEnabled()) {
            log.info("[VMB] enumMapping: considered={} mapped={} producedBuckets={}",
                    totalConsidered, totalMapped, out.size());
        }

        return out;
    }

    private static final class EnumRef {
        final String value;
        final float[] vec;

        EnumRef(String value, float[] vec) {
            this.value = value;
            this.vec = vec;
        }
    }

    // ============================================================
    // Generic closed-domain categorical harmonizer
    // ============================================================

    private List<SuggestedValueDTO> buildClosedDomainCategorical(String unionKey, List<EmbeddedColumn> sources) {
        if (sources == null || sources.isEmpty()) return Collections.emptyList();

        Map<String, Map<String, List<SuggestedRefDTO>>> bySource = new LinkedHashMap<>();
        Map<String, Integer> tokenGlobalFreq = new HashMap<>();
        Set<String> uniqueTokens = new LinkedHashSet<>();

        int valuesSeen = 0;

        for (EmbeddedColumn src : sources) {
            String srcKey = src.fileKey() + "::" + StringUtil.safe(src.column);
            Map<String, List<SuggestedRefDTO>> tokenToRefs =
                    bySource.computeIfAbsent(srcKey, k -> new LinkedHashMap<>());

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
                valuesSeen++;
            }
        }

        int uniq = uniqueTokens.size();
        if (log.isInfoEnabled()) {
            log.info("[VMB] closedDomain: union='{}' uniqueTokens={} valuesSeen={} sources={}",
                    unionKey, uniq, valuesSeen, sources.size());
        }
        if (log.isDebugEnabled()) {
            List<String> sample = new ArrayList<>(uniqueTokens);
            if (sample.size() > LOG_MAX_VALUES_SAMPLE) sample = sample.subList(0, LOG_MAX_VALUES_SAMPLE);
            log.debug("[VMB] closedDomain: tokens sample ({}): {}", sample.size(), sample);
        }

        if (uniq < MIN_DOMAIN_UNIQUE || uniq > MAX_DOMAIN_UNIQUE) {
            log.info("[VMB] closedDomain: skip (uniqueTokens {} not in [{}..{}])",
                    uniq, MIN_DOMAIN_UNIQUE, MAX_DOMAIN_UNIQUE);
            return Collections.emptyList();
        }

        if (shouldSkipEmbeddingAliasing(uniqueTokens)) {
            log.info("[VMB] closedDomain: skip (too many short tokens; MIN_LEN_FOR_EMBED_ALIAS={})",
                    MIN_LEN_FOR_EMBED_ALIAS);
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>(uniqueTokens);

        // Local cache for embeddings.
        Map<String, float[]> tokenVec = new HashMap<>();
        for (String t : tokens) tokenVec.put(t, embeddingService.embedSingleValue(t));

        UnionFind uf = new UnionFind(tokens.size());
        int unions = 0;
        int blockedPairs = 0;

        for (int i = 0; i < tokens.size(); i++) {
            for (int j = i + 1; j < tokens.size(); j++) {
                String a = tokens.get(i);
                String b = tokens.get(j);

                if (isObviousAlias(a, b)) {
                    uf.union(i, j);
                    unions++;
                    if (log.isDebugEnabled()) {
                        log.debug("[VMB] closedDomain: obviousAlias '{}' <-> '{}'", a, b);
                    }
                    continue;
                }

                if (shouldBlockEmbeddingAlias(a, b)) {
                    blockedPairs++;
                    continue;
                }

                double sim = MappingMathUtil.cosine(tokenVec.get(a), tokenVec.get(b));
                if (sim >= THRESH_TOKEN_ALIAS) {
                    uf.union(i, j);
                    unions++;
                    if (log.isDebugEnabled()) {
                        log.debug("[VMB] closedDomain: embedAlias '{}' <-> '{}' sim={}", a, b, round3(sim));
                    }
                }
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[VMB] closedDomain: alias unions={} blockedPairs={} threshold={}",
                    unions, blockedPairs, THRESH_TOKEN_ALIAS);
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

                if (log.isDebugEnabled()) {
                    log.debug("[VMB] closedDomain: component canon='{}' tokens={} supportRefs={}",
                            canon, comp, support);
                }
            }
        }

        if (!joinsAcrossAtLeastTwoFiles(canonToRefs)) {
            log.info("[VMB] closedDomain: skip (does not join across >=2 files)");
            return Collections.emptyList();
        }

        List<Map.Entry<String, List<SuggestedRefDTO>>> entries = new ArrayList<>(canonToRefs.entrySet());
        entries.removeIf(x -> canonSupport.getOrDefault(x.getKey(), 0) < MIN_SUPPORT_FOR_CANON);

        if (entries.size() < 2) {
            log.info("[VMB] closedDomain: skip (after MIN_SUPPORT_FOR_CANON={}, buckets={})",
                    MIN_SUPPORT_FOR_CANON, entries.size());
            return Collections.emptyList();
        }

        entries.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        List<SuggestedValueDTO> out = new ArrayList<>();
        for (Map.Entry<String, List<SuggestedRefDTO>> x : entries) {
            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(x.getKey());
            vd.setMapping(x.getValue());
            out.add(vd);
        }

        log.info("[VMB] closedDomain: SUCCESS union='{}' buckets={}", unionKey, out.size());
        return out;
    }

    private boolean shouldSkipEmbeddingAliasing(Set<String> uniqueTokens) {
        int shortCount = 0;
        int total = 0;
        for (String t : uniqueTokens) {
            String x = StringUtil.safe(t).trim();
            if (x.isEmpty()) continue;
            total++;
            if (x.length() < MIN_LEN_FOR_EMBED_ALIAS) shortCount++;
        }
        boolean skip = total > 0 && (shortCount / (double) total) >= 0.50;
        if (log.isDebugEnabled()) {
            log.debug("[VMB] closedDomain: shouldSkipEmbeddingAliasing total={} short(<{})={} frac={} skip={}",
                    total, MIN_LEN_FOR_EMBED_ALIAS, shortCount,
                    total == 0 ? 0.0 : (shortCount / (double) total), skip);
        }
        return skip;
    }

    private boolean shouldBlockEmbeddingAlias(String a, String b) {
        String x = StringUtil.safe(a).trim();
        String y = StringUtil.safe(b).trim();
        if (x.isEmpty() || y.isEmpty()) return true;
        return x.length() < MIN_LEN_FOR_EMBED_ALIAS || y.length() < MIN_LEN_FOR_EMBED_ALIAS;
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
        if (x.length() == 1 && y.length() >= 3) return y.charAt(0) == x.charAt(0);
        if (y.length() == 1 && x.length() >= 3) return x.charAt(0) == y.charAt(0);

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
    // Clustered fallback
    // ============================================================

    private List<SuggestedValueDTO> buildClusteredValueMappings(String unionName, List<EmbeddedColumn> sources) {
        List<ValueItem> items = new ArrayList<>();
        Map<String, Integer> freq = new HashMap<>();

        Map<String, float[]> vecCache = new HashMap<>();

        int srcCount = sources == null ? 0 : sources.size();
        if (log.isInfoEnabled()) {
            log.info("[VMB] clustering: union='{}' sources={}", unionName, srcCount);
        }

        if (sources != null) {
            for (EmbeddedColumn src : sources) {
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

                    float[] vec = vecCache.computeIfAbsent(nv, k -> embeddingService.embedSingleValue(k));
                    items.add(new ValueItem(nv, vec, ref));
                    used++;
                }
            }
        }

        if (items.isEmpty()) {
            log.info("[VMB] clustering: no items -> empty");
            return Collections.emptyList();
        }

        log.info("[VMB] clustering: collected items={} (cap={})", items.size(), MAX_VALUES_PER_UNION);

        List<Cluster> clusters = new ArrayList<>();
        int merges = 0;

        for (ValueItem it : items) {
            Cluster best = null;
            double bestSim = -1;

            for (Cluster c : clusters) {
                double sim = MappingMathUtil.cosine(it.vec, c.centroid);
                if (sim > bestSim) { bestSim = sim; best = c; }
            }

            if (best != null && bestSim >= THRESH_CLUSTER) {
                best.add(it);
                merges++;
                if (log.isTraceEnabled()) {
                    log.trace("[VMB] clustering: merge '{}' into cluster rep='{}' sim={}",
                            it.normalized, best.repHint(), round3(bestSim));
                }
            } else {
                Cluster c = new Cluster();
                c.add(it);
                clusters.add(c);
                if (log.isTraceEnabled()) {
                    log.trace("[VMB] clustering: new cluster with '{}'", it.normalized);
                }
            }
        }

        if (log.isInfoEnabled()) {
            log.info("[VMB] clustering: formed clusters={} merges={} threshold={}", clusters.size(), merges, THRESH_CLUSTER);
        }

        List<SuggestedValueDTO> out = new ArrayList<>();
        int shown = 0;

        for (Cluster c : clusters) {
            if (c.refs.isEmpty()) continue;

            String rep = pickRepresentative(c, freq);

            SuggestedValueDTO vd = new SuggestedValueDTO();
            vd.setName(rep);
            vd.setMapping(new ArrayList<>(c.refs));
            out.add(vd);

            if (log.isDebugEnabled() && shown < LOG_MAX_CLUSTERS_DETAIL) {
                shown++;
                log.debug("[VMB] clustering: bucket rep='{}' size={} distinctNormVals={} sampleVals={}",
                        rep, c.refs.size(), c.normalizedValues.size(), c.sampleNormVals(6));
            }
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

        String repHint() {
            if (normalizedValues.isEmpty()) return "";
            return normalizedValues.iterator().next();
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

    // ============================================================
    // Logging helpers
    // ============================================================

    private void debugPickedSources(String stage, String unionKey, String detectedType, List<EmbeddedColumn> picked) {
        if (!log.isDebugEnabled()) return;
        if (picked == null || picked.isEmpty()) {
            log.debug("[VMB] {}: picked sources empty (union='{}')", stage, unionKey);
            return;
        }
        int lim = Math.min(LOG_MAX_SOURCES_DETAIL, picked.size());
        for (int i = 0; i < lim; i++) {
            EmbeddedColumn c = picked.get(i);
            String stats = (c == null || c.stats == null) ? "stats=null" : statsSummary(c);
            log.debug("[VMB] {}: src[{}] {} concept='{}' {}",
                    stage, i, sourceKey(c), StringUtil.safe(c.concept), stats);
        }
        if (picked.size() > lim) {
            log.debug("[VMB] {}: ... {} more sources omitted", stage, picked.size() - lim);
        }
    }

    private void debugDomains(Map<String, ScaleDomain> domBySource) {
        if (!log.isDebugEnabled()) return;
        int shown = 0;
        for (Map.Entry<String, ScaleDomain> e : domBySource.entrySet()) {
            if (shown >= LOG_MAX_SOURCES_DETAIL) break;
            shown++;
            ScaleDomain d = e.getValue();
            if (d == null) {
                log.debug("[VMB] domain: src={} -> null", e.getKey());
            } else {
                int min = d.categories.get(0);
                int max = d.categories.get(d.categories.size() - 1);
                int step = d.categories.size() >= 2 ? (d.categories.get(1) - d.categories.get(0)) : 0;
                log.debug("[VMB] domain: src={} -> kind={} cats={} min={} max={} step={}",
                        e.getKey(), d.kind, d.categories.size(), min, max, step);
            }
        }
        if (domBySource.size() > shown) {
            log.debug("[VMB] domain: ... {} more sources omitted", domBySource.size() - shown);
        }
    }

    private String statsSummary(EmbeddedColumn c) {
        if (c == null || c.stats == null) return "stats=null";
        StringBuilder sb = new StringBuilder();
        sb.append("typeMarkers=");
        sb.append(c.stats.hasIntegerMarker ? "int" : "");
        sb.append(c.stats.hasDoubleMarker ? (sb.charAt(sb.length() - 1) == '=' ? "dbl" : ",dbl") : "");
        sb.append(c.stats.hasDateMarker ? (sb.charAt(sb.length() - 1) == '=' ? "date" : ",date") : "");
        sb.append(" min=").append(c.stats.numMin);
        sb.append(" max=").append(c.stats.numMax);
        sb.append(" stepHint=").append(c.stats.stepHint);
        return sb.toString();
    }

    private String sourceKey(EmbeddedColumn c) {
        if (c == null) return "null";
        return StringUtil.safe(c.nodeId) + "::" + StringUtil.safe(c.fileName) + "::" + StringUtil.safe(c.column);
    }

    private static String round3(double x) {
        return String.format(Locale.ROOT, "%.3f", x);
    }
}
