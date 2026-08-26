package com.jerin.trading.broker;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OptionChainEntry(
        LocalDate expiry,
        BigDecimal strikePrice,
        BigDecimal underlyingSpotPrice,
        OptionLeg call,
        OptionLeg put
) {
    public record OptionLeg(
            Long oi,
            Long changeOi,
            BigDecimal iv,
            BigDecimal ltp,
            Long volume
    ) {
    }
}
