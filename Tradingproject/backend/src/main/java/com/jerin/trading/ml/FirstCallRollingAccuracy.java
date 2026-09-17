package com.jerin.trading.ml;

import java.math.BigDecimal;

/**
 * Same idea as {@link RollingAccuracy}, but scored using each day's FIRST prediction call
 * (from {@link AiPredictionSnapshot}) instead of whichever call happened to be sitting in
 * {@link AiPrediction} when evaluation ran. For an intraday horizon re-predicted hourly, the
 * latest call is made with almost the whole day's price action already known and trivially
 * converges toward the actual close — scoring on it flatters the model without it having
 * forecast anything. The first call, made with the least information, is the fair test.
 *
 * {@code avgAbsRevisionGradient} is the average absolute distance between the day's first and
 * last call — how much the AI revised its own prediction over the course of the day. Not a
 * skill measure by itself, but the raw signal a future "revision trend" feature would be built
 * from once enough days of it have accumulated.
 */
public record FirstCallRollingAccuracy(
        String instrument,
        String horizon,
        int windowSize,
        int evaluatedCount,
        BigDecimal avgAiErrorAbs,
        BigDecimal avgBaselineErrorAbs,
        BigDecimal betterThanBaselinePct,
        BigDecimal directionAccuracyPct,
        BigDecimal avgAbsRevisionGradient
) {
}
