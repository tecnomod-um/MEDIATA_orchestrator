package org.taniwha.util;

import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;

public class CorrelationAnalysis {
    public static double pearsonsCorrelation(double[] x, double[] y) {
        PearsonsCorrelation pearsonsCorrelation = new PearsonsCorrelation();
        return pearsonsCorrelation.correlation(x, y);
    }

    public static double spearmansCorrelation(double[] x, double[] y) {
        SpearmansCorrelation spearmansCorrelation = new SpearmansCorrelation();
        return spearmansCorrelation.correlation(x, y);
    }
}
