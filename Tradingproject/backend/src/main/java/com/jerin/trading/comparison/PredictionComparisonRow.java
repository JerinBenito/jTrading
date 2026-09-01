package com.jerin.trading.comparison;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One model's prediction for a given day, normalized to the same shape regardless of which
 * system produced it — lets the frontend render one table comparing the deterministic model
 * against every AI horizon without special-casing each source.
 *
 * @param rangeLow/rangeHigh   only set for DETERMINISTIC (its calibrated range); null for AI rows
 * @param baselinePrice        only set for AI (its comparison point); null for DETERMINISTIC
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
        BigDecimal actualPrice,
        boolean evaluated,
        Boolean betterThanBaseline,
        Boolean directionCorrect
) {
}
