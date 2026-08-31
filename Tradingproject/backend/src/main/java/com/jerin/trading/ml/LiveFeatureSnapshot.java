package com.jerin.trading.ml;

/**
 * Today's current (still-forming) intraday feature state — same shape as {@link IntradayFeatureRow}
 * minus the outcome fields, since today's final close isn't known yet. This is what the AI model
 * actually predicts from live; {@link IntradayFeatureRow} (historical, closed days only) is what
 * it trains on.
 */
public record LiveFeatureSnapshot(
        String instrument,
        String tradingDate,
        int hoursSinceOpen,
        double returnSoFarPct,
        double volatilitySoFarPct,
        double rsi14,
        double emaSpreadPct,
        double bodyPct,
        double upperWickPct,
        double lowerWickPct,
        int last3UpCount,
        Double volumeSoFarRatio,
        double currentPrice
) {
}
