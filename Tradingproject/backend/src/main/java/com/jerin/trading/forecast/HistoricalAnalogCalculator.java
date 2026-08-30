package com.jerin.trading.forecast;

import java.util.Comparator;
import java.util.List;

/**
 * Conditions the same-day close estimate on how historically similar days actually finished,
 * instead of assuming zero further drift from the current price (what {@link IntradayReanchorCalculator}
 * does alone). "Similar" means: at the same hour-of-session checkpoint, the historical day had
 * moved from its own open by roughly the same amount today has moved from its own open so far.
 *
 * Deliberately simple k-nearest-neighbors on a single feature (today's intraday return-so-far),
 * not a fitted regression — easier to reason about, harder to overfit on ~500 historical days,
 * and directly matches the intuition being tested: "days that moved like this by this hour,
 * how much further did they typically move by the close?" Whether this actually beats the
 * plain zero-drift baseline is an empirical question, answered by
 * {@link HistoricalAnalogBacktestService} before this is ever trusted live.
 */
public final class HistoricalAnalogCalculator {

    /** Need at least this many historical (day, hour) observations before ever attempting an estimate. */
    public static final int MIN_POOL_SIZE = 30;

    /** Deliberately modest — not tuned to this dataset, same spirit as MomentumForecastModel's damping constant. */
    static final int K_NEIGHBORS = 15;

    private HistoricalAnalogCalculator() {
    }

    /**
     * @param historicalPool each entry is a past day's (return-so-far, remaining-drift-to-close)
     *                       at the *same* hour checkpoint as `todayReturnSoFar` — never today itself
     * @return the average remaining drift among the nearest neighbors by return-so-far, or null
     *         if the pool isn't large enough yet to trust
     */
    public static Double estimateRemainingDrift(List<ReturnDriftPair> historicalPool, double todayReturnSoFar) {
        if (historicalPool.size() < MIN_POOL_SIZE) {
            return null;
        }
        int k = Math.min(K_NEIGHBORS, historicalPool.size());
        return historicalPool.stream()
                .sorted(Comparator.comparingDouble(pair -> Math.abs(pair.returnSoFar() - todayReturnSoFar)))
                .limit(k)
                .mapToDouble(ReturnDriftPair::remainingDrift)
                .average()
                .orElse(0.0);
    }
}
