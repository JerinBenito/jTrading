package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Statistically-gated bias correction: given a window of recent (actual - predicted) errors,
 * estimates whether there's a real, non-noise bias worth correcting for — and if so, applies
 * only a fraction of it (shrinkage), not the full estimate.
 *
 * This replaces a naive "always apply the raw mean of the last N errors" approach, which was
 * found (2026-08-25, real production data) to make predictions worse, not better — because at
 * hourly granularity, prices are close to a random walk (confirmed in the Phase 1 backtest:
 * a momentum-based model also lost to plain random walk), so the "true" bias is near zero and
 * a small-sample average of near-zero-mean noise is itself just noise. Applying that noise
 * unconditionally as a correction adds error rather than removing it.
 *
 * The fix: only correct when the estimated bias is large relative to its own uncertainty
 * (mean vs standard error, the same logic behind a one-sample t-test), and even then, only
 * lean into it partially (shrinkage) rather than fully trusting a noisy point estimate.
 */
public final class BiasCorrectionCalculator {

    /** Need at least this many observations before ever attempting a correction. */
    public static final int MIN_WINDOW = 30;

    /** Mean must exceed this many standard errors before it's treated as real, not noise. */
    static final double SIGNIFICANCE_MULTIPLIER = 1.5;

    /** Only lean into the estimated bias by this fraction, even when it clears the significance bar. */
    static final double SHRINKAGE_FACTOR = 0.5;

    private BiasCorrectionCalculator() {
    }

    /** @param recentErrors raw (actual - predicted) values, in price units, most recent history available */
    public static BigDecimal calculate(List<Double> recentErrors) {
        if (recentErrors.size() < MIN_WINDOW) {
            return BigDecimal.ZERO;
        }

        double mean = recentErrors.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumSquaredDeviations = recentErrors.stream()
                .mapToDouble(e -> Math.pow(e - mean, 2))
                .sum();
        double sampleVariance = sumSquaredDeviations / (recentErrors.size() - 1);
        double standardError = Math.sqrt(sampleVariance / recentErrors.size());

        if (standardError == 0 || Math.abs(mean) < SIGNIFICANCE_MULTIPLIER * standardError) {
            return BigDecimal.ZERO; // not distinguishable from noise — don't "correct" for randomness
        }

        return BigDecimal.valueOf(mean * SHRINKAGE_FACTOR).setScale(4, RoundingMode.HALF_UP);
    }
}
