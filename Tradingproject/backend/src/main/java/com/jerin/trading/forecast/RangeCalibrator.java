package com.jerin.trading.forecast;

/**
 * Online calibration of the prediction range width — the one part of the forecast that, until
 * now, never got checked against reality: {@code predictedClose ± ATR} was applied unchanged
 * regardless of how often the actual price really landed inside it. This closes that loop with
 * a standard adaptive-conformal-style update (Gibbs &amp; Candès-style online quantile
 * tracking): after each evaluated prediction, nudge a multiplier on ATR up if the actual value
 * fell outside the range, down (more gently) if it fell inside — sized so that, at equilibrium,
 * the long-run hit rate converges to {@link #TARGET_COVERAGE}. Deterministic, no ML — the same
 * class of technique as {@link BiasCorrectionCalculator}, applied to width instead of center.
 *
 * Genuinely gets more precise with more data: each new evaluated prediction is one more nudge
 * toward whatever multiplier actually achieves the target hit rate for that instrument/interval,
 * rather than assuming raw ATR (multiplier 1.0) is automatically the right width.
 */
public final class RangeCalibrator {

    public static final double DEFAULT_MULTIPLIER = 1.0;
    public static final double MIN_MULTIPLIER = 0.5;
    public static final double MAX_MULTIPLIER = 3.0;

    /** Fraction of predictions we want the actual value to fall inside the range, in the long run. */
    static final double TARGET_COVERAGE = 0.90;

    /** How much one observation moves the multiplier — small, so it converges rather than chases noise. */
    static final double LEARNING_RATE = 0.02;

    private RangeCalibrator() {
    }

    /** One online step: given the multiplier used for the prediction just evaluated and whether it was covered, returns the next multiplier to use. */
    public static double update(double previousMultiplier, boolean wasCovered) {
        double missIndicator = wasCovered ? 0.0 : 1.0;
        double next = previousMultiplier + LEARNING_RATE * (missIndicator - (1 - TARGET_COVERAGE));
        return Math.min(MAX_MULTIPLIER, Math.max(MIN_MULTIPLIER, next));
    }
}
