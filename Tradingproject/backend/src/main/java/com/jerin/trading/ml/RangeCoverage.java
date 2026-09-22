package com.jerin.trading.ml;

import java.math.BigDecimal;

/**
 * How well the AI's learned range (see {@link AiPrediction#getPredictedRangeLow()}) has actually
 * held up — the same kind of check the deterministic model's fixed-formula range already gets in
 * {@code ForecastController}'s range-calibration backtest, applied to a genuinely learned one
 * instead. {@code coveragePct} is the fraction of evaluated days the actual outcome fell inside
 * the range; {@code avgWidthPct} is how wide the range was, as a % of price — a range that's
 * always wide "wins" on coverage trivially, so the two numbers only mean something together.
 */
public record RangeCoverage(
        String instrument,
        String horizon,
        int windowSize,
        int evaluatedCount,
        BigDecimal coveragePct,
        BigDecimal avgWidthPct
) {
}
