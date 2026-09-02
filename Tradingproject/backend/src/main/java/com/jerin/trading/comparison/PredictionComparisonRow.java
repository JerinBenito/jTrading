package com.jerin.trading.comparison;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One model's prediction for a given day, normalized to the same shape regardless of which
 * system produced it — lets the frontend render one table comparing the deterministic model
 * against every AI horizon without special-casing each source.
 *
 * @param rangeLow/rangeHigh   DETERMINISTIC: its calibrated range. AI: a historical-error band
 *                             (± average absolute error over recent evaluated predictions),
 *                             only populated once enough evaluated history exists — null
 *                             otherwise rather than a fabricated guess.
 * @param baselinePrice        only set for AI (its comparison point); null for DETERMINISTIC
 * @param currentPrice         the latest known real price for {@code targetDate} (from candle
 *                             data), independent of whether this prediction has been formally
 *                             scored yet — populated as soon as the day has any candle, updates
 *                             through the session and holds at the close price after market close.
 * @param betterThanBaseline/directionCorrect  only meaningful (non-null) for AI rows
 */
public record PredictionComparisonRow(
        String source,
        String label,
        String modelName,
        LocalDate targetDate,
        BigDecimal predictedPrice,
        BigDecimal rangeLow,
        BigDecimal rangeHigh,
        BigDecimal baselinePrice,
        BigDecimal currentPrice,
        BigDecimal actualPrice,
        boolean evaluated,
        Boolean betterThanBaseline,
        Boolean directionCorrect
) {
}
