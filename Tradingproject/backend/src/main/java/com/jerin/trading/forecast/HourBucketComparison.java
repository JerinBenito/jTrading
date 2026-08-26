package com.jerin.trading.forecast;

import java.math.BigDecimal;

/**
 * Static (once-a-day) vs. re-anchored (recomputed at this hour) prediction of the *same day's
 * final close*, both measured against what actually happened — for every trading day in
 * history, bucketed by how many hours had elapsed since that day's open.
 */
public record HourBucketComparison(
        int hoursSinceOpen,
        int sampleSize,
        BigDecimal staticMeanAbsErrorPct,
        BigDecimal staticCoveragePct,
        BigDecimal reanchoredMeanAbsErrorPct,
        BigDecimal reanchoredCoveragePct,
        BigDecimal reanchoredAvgRangeWidthPct
) {
}
