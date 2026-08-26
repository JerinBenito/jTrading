package com.jerin.trading.broker;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Candle(
        OffsetDateTime ts,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        Long volume,
        Long openInterest
) {
}
