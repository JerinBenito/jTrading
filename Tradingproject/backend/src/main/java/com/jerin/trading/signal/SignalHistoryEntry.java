package com.jerin.trading.signal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A past prediction plus its outcome, if evaluated yet — null outcome fields mean "not resolved yet". */
public record SignalHistoryEntry(
        Long id,
        String instrument,
        OffsetDateTime ts,
        String patternId,
        String predictedDirection,
        String confidenceTier,
        int sampleSize,
        String actualDirection,
        BigDecimal actualMovePct
) {
}
