package com.jerin.trading.signal;

import java.math.BigDecimal;

/** Never an exact predicted price — direction, a confidence tier backed by a real sample size, and a range. */
public record SignalResponse(
        Long predictionId,
        String instrument,
        String patternId,
        String predictedDirection,
        String confidenceTier,
        int sampleSize,
        BigDecimal expectedRangeLow,
        BigDecimal expectedRangeHigh
) {
}
