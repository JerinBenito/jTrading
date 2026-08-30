package com.jerin.trading.monitor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A read-only, latest-known-state view of one instrument for the monitoring dashboard —
 * deliberately separate from {@link com.jerin.trading.signal.SignalService}'s pattern signals:
 * no win-rate confidence tier, nothing persisted, just "what does this instrument look like
 * right now." Null fields mean not enough stored candles yet to compute that indicator.
 */
public record BasketSnapshot(
        String symbol,
        OffsetDateTime lastCandleTs,
        BigDecimal lastClose,
        BigDecimal changePct,
        BigDecimal ema9,
        BigDecimal ema21,
        String trend,
        BigDecimal rsi14,
        String rsiZone,
        int candleCount
) {
}
