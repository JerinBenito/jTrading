package com.jerin.trading.forecast;

/**
 * Re-estimates the same-day close prediction mid-session using the price actually observed so
 * far, instead of leaving the market-open prediction untouched all day. Grounded in the same
 * idea used across quantitative finance and forecasting literature for "day-ahead → intraday"
 * updates: condition the remaining-horizon estimate on what's now known, and shrink the
 * remaining uncertainty as less trading time is left (classic Brownian-motion time-scaling —
 * variance grows linearly with time, so the range should scale with the square root of the
 * remaining time fraction, same law behind Black-Scholes volatility scaling).
 *
 * Deliberately simple and deterministic (no ML): the point estimate is just the latest known
 * price (re-anchored random walk), and the range shrinks by sqrt(remaining session fraction).
 * Whether this actually beats the static once-a-day prediction is an empirical question,
 * answered by {@link IntradayReanchorBacktestService} before this is trusted live.
 */
public final class IntradayReanchorCalculator {

    /** Floor on remaining session fraction so the range never collapses toward zero right before close. */
    static final double MIN_REMAINING_FRACTION = 0.05;

    private IntradayReanchorCalculator() {
    }

    /** @return the fraction (0..1) of today's trading session still remaining */
    public static double remainingFraction(long elapsedMinutes, long totalSessionMinutes) {
        if (totalSessionMinutes <= 0) {
            return 0;
        }
        double elapsed = Math.min(Math.max(elapsedMinutes, 0), totalSessionMinutes);
        return 1.0 - (elapsed / (double) totalSessionMinutes);
    }

    /** @return the range half-width for the rest of the session, given yesterday's daily ATR and how much of today is left */
    public static double remainingRangeWidth(double dailyAtr, double remainingFraction) {
        double clamped = Math.max(remainingFraction, MIN_REMAINING_FRACTION);
        return dailyAtr * Math.sqrt(clamped);
    }
}
