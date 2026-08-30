package com.jerin.trading.forecast;

import java.math.BigDecimal;

/**
 * Three-way, same-hour comparison: the plain volatility-scaled re-anchor (zero drift), the
 * simple single-feature historical analog (return-so-far only), and the rich multi-feature
 * historical analog (return-so-far + volatility-so-far + RSI + EMA spread) — all predicting
 * that day's final close, bucketed by hours elapsed since open.
 */
public record RichHistoricalAnalogHourResult(
        int hoursSinceOpen,
        int sampleSize,
        double avgNeighborPoolSize,
        BigDecimal baselineMeanAbsErrorPct,
        BigDecimal baselineCoveragePct,
        BigDecimal simpleAnalogMeanAbsErrorPct,
        BigDecimal simpleAnalogCoveragePct,
        BigDecimal richAnalogMeanAbsErrorPct,
        BigDecimal richAnalogCoveragePct,
        BigDecimal avgRangeWidthPct
) {
}
