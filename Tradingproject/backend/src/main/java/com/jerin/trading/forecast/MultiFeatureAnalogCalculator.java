package com.jerin.trading.forecast;

import java.util.Comparator;
import java.util.List;

/**
 * Same idea as {@link HistoricalAnalogCalculator} (k-nearest-neighbors on "days that looked
 * like today, how did they finish"), extended to multiple features instead of just intraday
 * return-so-far — volatility-so-far and RSI/EMA trend state, per the owner's original request
 * for "similar morning move, similar volatility regime, similar pattern/indicator state."
 *
 * Features are on wildly different scales (a return is ~0.01, RSI is 0-100), so raw Euclidean
 * distance would let whichever feature has the largest scale dominate the match regardless of
 * how informative it actually is. Each dimension is standardized (divided by its own standard
 * deviation across the *current* historical pool) before computing distance — computed fresh
 * from the pool every call, so it stays walk-forward safe (only ever reflects days strictly
 * before today, never today's own value).
 */
public final class MultiFeatureAnalogCalculator {

    public static final int MIN_POOL_SIZE = 30;
    static final int K_NEIGHBORS = 15;

    private MultiFeatureAnalogCalculator() {
    }

    public static Double estimateRemainingDrift(List<HistoricalDayObservation> historicalPool, double[] todayFeatures) {
        if (historicalPool.size() < MIN_POOL_SIZE) {
            return null;
        }
        double[] stdevs = standardDeviations(historicalPool, todayFeatures.length);
        int k = Math.min(K_NEIGHBORS, historicalPool.size());

        return historicalPool.stream()
                .sorted(Comparator.comparingDouble(obs -> normalizedDistance(obs.features(), todayFeatures, stdevs)))
                .limit(k)
                .mapToDouble(HistoricalDayObservation::remainingDrift)
                .average()
                .orElse(0.0);
    }

    private static double normalizedDistance(double[] a, double[] b, double[] stdevs) {
        double sumSquares = 0;
        for (int i = 0; i < a.length; i++) {
            double denom = stdevs[i] > 1e-9 ? stdevs[i] : 1e-9;
            double diff = (a[i] - b[i]) / denom;
            sumSquares += diff * diff;
        }
        return Math.sqrt(sumSquares);
    }

    private static double[] standardDeviations(List<HistoricalDayObservation> pool, int dimensions) {
        double[] means = new double[dimensions];
        for (HistoricalDayObservation obs : pool) {
            for (int i = 0; i < dimensions; i++) {
                means[i] += obs.features()[i];
            }
        }
        for (int i = 0; i < dimensions; i++) {
            means[i] /= pool.size();
        }

        double[] variances = new double[dimensions];
        for (HistoricalDayObservation obs : pool) {
            for (int i = 0; i < dimensions; i++) {
                double diff = obs.features()[i] - means[i];
                variances[i] += diff * diff;
            }
        }

        double[] stdevs = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            stdevs[i] = Math.sqrt(variances[i] / pool.size());
        }
        return stdevs;
    }
}
