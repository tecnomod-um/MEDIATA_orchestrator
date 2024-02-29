package org.taniwha.util;

import org.apache.commons.math3.stat.inference.ChiSquareTest;

public class ChiSquaredTest {
    public static double[] chiSquared(long[][] observed) {
        ChiSquareTest chiSquareTest = new ChiSquareTest();
        return new double[]{
                chiSquareTest.chiSquare(observed),
                chiSquareTest.chiSquareTest(observed)
        };
    }
}