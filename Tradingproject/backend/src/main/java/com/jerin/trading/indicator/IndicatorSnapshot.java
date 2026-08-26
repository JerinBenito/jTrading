package com.jerin.trading.indicator;

import java.math.BigDecimal;

/** Null fields mean not enough stored data yet to compute that indicator — never fabricated. */
public record IndicatorSnapshot(
        String instrument,
        String interval,
        int candleCount,
        BigDecimal ema9,
        BigDecimal ema21,
        BigDecimal rsi14,
        BigDecimal atr14,
        BigDecimal pcr
) {
}
