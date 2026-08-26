package com.jerin.trading.forecast;

import java.math.BigDecimal;

/** Same shape as {@link ForecastBacktestResult} plus the multiplier the online calibrator ended up at — the number that would carry into live use. */
public record RangeCalibrationBacktestResult(
        String modelName,
        int sampleSize,
        BigDecimal meanAbsoluteErrorPct,
        BigDecimal pctActualWithinPredictedRange,
        BigDecimal avgRangeWidthPct,
        BigDecimal finalMultiplier
) {
}
