package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A day's morning close-prediction alongside how the actual price moved toward (or away from)
 * it, hour by hour — plus a live, continuously re-anchored estimate ({@code currentEstimated*})
 * based on the latest known price and however much of the session remains, validated via
 * {@link IntradayReanchorBacktestService} (2026-08-26). Null while the day is still resolving
 * and there isn't yet a prior day's ATR to base it on, or once the day is fully evaluated
 * (actualClose set) — at that point the static/actual comparison above is the complete picture.
 */
public record DailyTrajectory(
        String instrument,
        LocalDate date,
        BigDecimal predictedClose,
        BigDecimal rangeLow,
        BigDecimal rangeHigh,
        BigDecimal actualClose,
        List<TrajectoryPoint> points,
        BigDecimal currentEstimatedClose,
        BigDecimal currentEstimatedRangeLow,
        BigDecimal currentEstimatedRangeHigh
) {
}
