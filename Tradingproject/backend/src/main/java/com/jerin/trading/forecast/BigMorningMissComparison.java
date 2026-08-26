package com.jerin.trading.forecast;

import java.math.BigDecimal;

/**
 * Answers the specific question: on days where the 10:15 IST price already broke outside the
 * static prediction's expected range, does sticking with the static (unchanged) prediction, or
 * re-anchoring to that 10:15 price, end up closer to the actual final close?
 */
public record BigMorningMissComparison(
        int sampleSize,
        BigDecimal staticMeanAbsErrorPct,
        BigDecimal staticCoveragePct,
        BigDecimal reanchoredMeanAbsErrorPct,
        BigDecimal reanchoredCoveragePct
) {
}
