package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;

import java.math.BigDecimal;
import java.util.List;

/** Candles and indicator series, all aligned by index (ascending, oldest first) — same shape as PatternContext. */
public record ForecastContext(
        List<OhlcvCandle> candles,
        List<BigDecimal> ema9,
        List<BigDecimal> ema21,
        List<BigDecimal> atr14
) {
}
