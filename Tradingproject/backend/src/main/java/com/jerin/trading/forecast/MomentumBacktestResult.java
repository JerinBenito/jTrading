package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param overlappingSampleSize  walk-forward samples (one per trading day), heavily autocorrelated
 *                               since consecutive windows share almost all their days
 * @param nonOverlappingSampleSize a rough count of genuinely independent windows (total days /
 *                                 horizon) — the honest sample size for judging real significance,
 *                                 much smaller than the overlapping count
 */
public record MomentumBacktestResult(
        int lookbackDays,
        int horizonDays,
        int overlappingSampleSize,
        int nonOverlappingSampleSize,
        BigDecimal correlationCoefficient,
        List<MomentumBucketResult> buckets
) {
}
