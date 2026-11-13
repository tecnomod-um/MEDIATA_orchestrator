package org.taniwha.service;

import java.util.ArrayList;
import java.util.List;
import org.taniwha.service.ClinicalScaleCatalog.ScaleKind;

final class ScaleDomain {
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
