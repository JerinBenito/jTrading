package com.jerin.trading.forecast;

import java.math.BigDecimal;

public record ForecastBacktestResult(
        String modelName,
        int sampleSize,
        BigDecimal meanAbsoluteErrorPct,
        BigDecimal pctActualWithinPredictedRange,
        BigDecimal avgRangeWidthPct
) {
}
