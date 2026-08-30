package com.jerin.trading.forecast;

import java.math.BigDecimal;

/**
 * Baseline (zero drift) vs. the existing 4-feature indicator analog (return-so-far, volatility,
 * RSI, EMA spread — see {@link RichHistoricalAnalogHourResult}) vs. a literal candlestick/chart
 * shape analog: the same 4 features plus the current candle's body/wick proportions and a
 * short-term (last-3-candle) up-count — testing whether actual candle *shape*, not just
 * indicator values, carries information the indicator-only feature set misses.
 */
public record ChartShapeAnalogHourResult(
        int hoursSinceOpen,
        int sampleSize,
        double avgNeighborPoolSize,
        BigDecimal baselineMeanAbsErrorPct,
        BigDecimal baselineCoveragePct,
        BigDecimal indicatorAnalogMeanAbsErrorPct,
        BigDecimal indicatorAnalogCoveragePct,
        BigDecimal shapeAnalogMeanAbsErrorPct,
        BigDecimal shapeAnalogCoveragePct,
        BigDecimal avgRangeWidthPct
) {
}
