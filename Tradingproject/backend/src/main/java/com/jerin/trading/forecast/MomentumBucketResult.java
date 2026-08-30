package com.jerin.trading.forecast;

import java.math.BigDecimal;

/** Days grouped into quartiles by their past-N-day return, and how those groups' *next*-N-day return actually turned out. */
public record MomentumBucketResult(
        String bucket,
        int sampleSize,
        BigDecimal avgPastReturnPct,
        BigDecimal avgFutureReturnPct,
        BigDecimal winRatePct
) {
}
