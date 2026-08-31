package com.jerin.trading.ml;

import java.math.BigDecimal;

/**
 * Aggregate accuracy over the last {@code window} evaluated predictions — the thing that
 * actually matters, per the project's own standard: never judge the model on a single
 * prediction, only on a pattern across many.
 */
public record RollingAccuracy(
        String instrument,
        String horizon,
        int windowSize,
        int evaluatedCount,
        BigDecimal avgAiErrorAbs,
        BigDecimal avgBaselineErrorAbs,
        BigDecimal betterThanBaselinePct,
        BigDecimal directionAccuracyPct
) {
}
