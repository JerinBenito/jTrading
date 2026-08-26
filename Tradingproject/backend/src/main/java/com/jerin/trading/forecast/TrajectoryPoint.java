package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One hour's actual price plotted against the morning's daily-close prediction. */
public record TrajectoryPoint(
        OffsetDateTime ts,
        BigDecimal actualClose,
        BigDecimal deviationFromPrediction,
        BigDecimal deviationPct,
        boolean withinPredictedRange
) {
}
