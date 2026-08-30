package com.jerin.trading.forecast;

import java.math.BigDecimal;

/**
 * Same-hour comparison of the plain volatility-scaled re-anchor (zero further drift assumed)
 * against the historical-analog estimate (informed by how similar past days finished), both
 * predicting that day's final close, bucketed by how many hours had elapsed since open.
 */
public record HistoricalAnalogHourResult(
        int hoursSinceOpen,
        int sampleSize,
        double avgNeighborPoolSize,
        BigDecimal baselineMeanAbsErrorPct,
        BigDecimal baselineCoveragePct,
        BigDecimal analogMeanAbsErrorPct,
        BigDecimal analogCoveragePct,
        BigDecimal avgRangeWidthPct
) {
}
