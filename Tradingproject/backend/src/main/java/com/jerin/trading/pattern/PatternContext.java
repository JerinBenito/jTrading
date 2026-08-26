package com.jerin.trading.pattern;

import com.jerin.trading.domain.OhlcvCandle;

import java.math.BigDecimal;
import java.util.List;

/** Candles and indicator series, all aligned by index (ascending, oldest first). */
public record PatternContext(
        List<OhlcvCandle> candles,
        List<BigDecimal> ema9,
        List<BigDecimal> ema21,
        List<BigDecimal> rsi14
) {
}
