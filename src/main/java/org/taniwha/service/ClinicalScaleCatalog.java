package org.taniwha.service;

import org.taniwha.model.EmbeddedColumn;
import org.taniwha.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recognizes well-known clinical score domains from column labels plus discovered numeric ranges.
 */
final class ClinicalScaleCatalog {

    private ClinicalScaleCatalog() {
    }

    static ScaleDomain infer(EmbeddedColumn src) {
        if (src == null || src.stats == null) return null;
        if (!src.stats.isHasIntegerMarker()) return null;
        if (src.stats.getNumMin() == null || src.stats.getNumMax() == null) return null;

        int min = (int) Math.round(src.stats.getNumMin());
        int max = (int) Math.round(src.stats.getNumMax());
        if (max < min) return null;

        Integer hintStep = src.stats.getStepHint();
        String label = scaleLabel(src);

        if (mentionsAny(label, "nihss", "nih stroke scale", "national institutes health stroke")) {
            if (min == 0 && max == 42) return ScaleDomain.linear(0, 42, 1, ScaleKind.NIHSS);
        }

        if (mentionsAny(label, "glasgow coma scale", "gcs")) {
            if (min == 3 && max == 15) return ScaleDomain.linear(3, 15, 1, ScaleKind.GCS);
        }

        if (mentionsAny(label, "modified rankin", "rankin", "mrs")) {
            if (min == 0 && max == 6) return ScaleDomain.linear(0, 6, 1, ScaleKind.MRS);
        }

        if (mentionsAny(label, "pain", "numeric rating scale", "nrs", "vas")) {
            if (min == 0 && (max == 10 || max == 20 || max == 100)) {
                int step = max == 100 ? 10 : 1;
                return ScaleDomain.linear(0, max, step, ScaleKind.PAIN_NRS);
            }
        }

        if (mentionsAny(label, "sofa", "sequential organ failure")) {
            if (min == 0 && max == 24) return ScaleDomain.linear(0, 24, 1, ScaleKind.SOFA);
        }

        if (mentionsAny(label, "apache ii", "apache 2", "apache score", "apache")) {
            if (min == 0 && max == 71) return ScaleDomain.linear(0, 71, 1, ScaleKind.APACHE_II);
        }

        if (mentionsAny(label, "news2", "news 2", "national early warning")) {
            if (min == 0 && max <= 20) return ScaleDomain.linear(0, max, 1, ScaleKind.NEWS2);
        }

        if (mentionsAny(label, "phq 9", "phq9", "patient health questionnaire 9")) {
            if (min == 0 && max == 27) return ScaleDomain.linear(0, 27, 1, ScaleKind.PHQ9);
        }

        if (mentionsAny(label, "gad 7", "gad7", "generalized anxiety disorder 7")) {
            if (min == 0 && max == 21) return ScaleDomain.linear(0, 21, 1, ScaleKind.GAD7);
        }

        if (mentionsAny(label, "moca", "montreal cognitive assessment")) {
            if (min == 0 && max == 30) return ScaleDomain.linear(0, 30, 1, ScaleKind.MOCA);
        }

        if (mentionsAny(label, "mmse", "mini mental state", "mini mental status")) {
            if (min == 0 && max == 30) return ScaleDomain.linear(0, 30, 1, ScaleKind.MMSE);
        }

        if (mentionsAny(label, "braden")) {
            if (min == 6 && max == 23) return ScaleDomain.linear(6, 23, 1, ScaleKind.BRADEN);
        }

        if (mentionsAny(label, "morse fall", "morse")) {
            if (min == 0 && max == 125) return ScaleDomain.linear(0, 125, 5, ScaleKind.MORSE_FALL);
        }

        if (mentionsAny(label, "cha2ds2 vasc", "cha2ds2vasc", "chads vasc", "chadsvasc")) {
            if (min == 0 && max == 9) return ScaleDomain.linear(0, 9, 1, ScaleKind.CHA2DS2_VASC);
        }

        if (mentionsAny(label, "has bled", "hasbled")) {
            if (min == 0 && max == 9) return ScaleDomain.linear(0, 9, 1, ScaleKind.HAS_BLED);
        }

        if (mentionsAny(label, "qsofa", "quick sofa", "quick sequential organ failure")) {
            if (min == 0 && max == 3) return ScaleDomain.linear(0, 3, 1, ScaleKind.QSOFA);
        }

        if (mentionsAny(label, "apgar")) {
            if (min == 0 && max == 10) return ScaleDomain.linear(0, 10, 1, ScaleKind.APGAR);
        }

        if (mentionsAny(label, "fim", "functional independence")) {
            if (min == 1 && max == 7) return ScaleDomain.linear(min, max, 1, ScaleKind.FIM);
        }

        if (mentionsAny(label, "barthel")) {
            if (min == 0 && (max == 5 || max == 10 || max == 15)) {
                return ScaleDomain.linear(0, max, 5, ScaleKind.BARTHEL_ITEM);
            }

            if (min == 0 && max == 100) {
                int step = (hintStep != null && hintStep > 0) ? hintStep : 5;
                return ScaleDomain.linear(0, 100, step, ScaleKind.BARTHEL_TOTAL);
            }
        }

        if (min == 1 && max == 7) return ScaleDomain.linear(min, max, 1, ScaleKind.FIM);

        if (min == 0 && (max == 5 || max == 10 || max == 15)) {
            return ScaleDomain.linear(0, max, 5, ScaleKind.BARTHEL_ITEM);
        }

        if (min == 0 && max == 100) {
            int step = (hintStep != null && hintStep > 0) ? hintStep : 5;
            return ScaleDomain.linear(0, 100, step, ScaleKind.BARTHEL_TOTAL);
        }

        int span = max - min;
        if (span >= 1 && span <= 20) {
            return ScaleDomain.linear(min, max, 1, ScaleKind.GENERIC);
        }

        return null;
    }

    private static String scaleLabel(EmbeddedColumn src) {
        return (StringUtil.safe(src.column) + " " + StringUtil.safe(src.concept))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static boolean mentionsAny(String label, String... needles) {
        String x = StringUtil.safe(label);
        for (String needle : needles) {
            String n = StringUtil.safe(needle).toLowerCase(Locale.ROOT).trim();
            if (!n.isEmpty() && x.contains(n)) return true;
        }
        return false;
    }

    enum ScaleKind {
        FIM,
        BARTHEL_ITEM,
        BARTHEL_TOTAL,
        NIHSS,
        GCS,
        MRS,
        PAIN_NRS,
        SOFA,
        APACHE_II,
        NEWS2,
        PHQ9,
        GAD7,
        MOCA,
        MMSE,
        BRADEN,
        MORSE_FALL,
        CHA2DS2_VASC,
        HAS_BLED,
        QSOFA,
        APGAR,
        GENERIC
    }

    static final class ScaleDomain {
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
}
