package com.jerin.trading.pattern;

import java.math.BigDecimal;

/**
 * A pattern's historical win rate, segmented by whether it fired on above-average volume —
 * "volume confirmation" is a classic TA idea (a signal on unusually high volume is traditionally
 * considered more reliable), tested here against real data before trusting it for anything.
 */
public record VolumeConfirmationResult(
        String patternId,
        String volumeBucket,
        int sampleSize,
        BigDecimal winRatePct,
        BigDecimal avgMovePct
) {
}
