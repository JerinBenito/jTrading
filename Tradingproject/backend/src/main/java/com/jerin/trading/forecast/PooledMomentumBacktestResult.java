package com.jerin.trading.forecast;

import java.math.BigDecimal;
import java.util.List;

/**
 * Same test as {@link MomentumBacktestResult} but with (pastReturn, futureReturn) pairs pooled
 * across NIFTY, BANKNIFTY, and the resolved NIFTY 50 basket stocks — increases the cross-sectional
 * sample size, but does NOT extend any single instrument's own history: {@code instrumentsUsed}
 * series each still only cover whatever date range was backfilled for them.
 *
 * @param instrumentsUsed     how many instruments had enough daily bars to contribute pairs
 * @param instrumentsSkipped  instrument tags that were resolved/ingested but had too little history
 * @param nonOverlappingSampleSize sum of each instrument's own (bars / horizon) — still an
 *                                 upper-bound estimate, since it ignores cross-instrument correlation
 *                                 (all NSE stocks tend to move together, so pooling more symbols
 *                                 doesn't buy fully independent samples the way more calendar time would)
 */
public record PooledMomentumBacktestResult(
        int lookbackDays,
        int horizonDays,
        int instrumentsUsed,
        List<String> instrumentsSkipped,
        int overlappingSampleSize,
        int nonOverlappingSampleSize,
        BigDecimal correlationCoefficient,
        List<MomentumBucketResult> buckets
) {
}
