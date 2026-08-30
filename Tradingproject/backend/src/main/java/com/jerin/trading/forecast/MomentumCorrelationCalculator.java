package com.jerin.trading.forecast;

import java.util.List;

/**
 * Pearson correlation between two equal-length series — used to test whether past N-day return
 * ("momentum") has any relationship with the *next* N-day return, the classic time-series
 * momentum question at multi-month horizons. Purely descriptive statistics, no fitting/training —
 * matches the project's "deterministic first" discipline for Phase C.
 */
public final class MomentumCorrelationCalculator {

    private MomentumCorrelationCalculator() {
    }

    public static double pearsonCorrelation(List<Double> x, List<Double> y) {
        int n = x.size();
        if (n == 0 || n != y.size()) {
            return 0;
        }
        double meanX = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double meanY = y.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        double numerator = 0;
        double sumSquaresX = 0;
        double sumSquaresY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - meanX;
            double dy = y.get(i) - meanY;
            numerator += dx * dy;
            sumSquaresX += dx * dx;
            sumSquaresY += dy * dy;
        }

        double denominator = Math.sqrt(sumSquaresX * sumSquaresY);
        return denominator == 0 ? 0 : numerator / denominator;
    }
}
