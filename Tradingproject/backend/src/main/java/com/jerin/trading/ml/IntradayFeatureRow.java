package com.jerin.trading.ml;

/**
 * One (instrument, trading day, hour-of-session) observation: the same feature set already
 * tested manually via k-NN ({@link com.jerin.trading.forecast.ChartShapeAnalogBacktestService}),
 * exported raw so a real trainable model can be tried instead of a fixed nearest-neighbor
 * average. {@code remainingDriftPct} is the actual (already-known, historical) outcome — how
 * much further price moved from this checkpoint to that day's final close.
 */
public record IntradayFeatureRow(
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
        /** Today's volume-so-far (through this hour) divided by the trailing 20-day average total
         * daily volume — "is today unusually busy so far, or unusually quiet." Null when the
         * instrument carries no real volume (the NIFTY/BANKNIFTY index itself) or there isn't yet
         * 20 prior days of history. */
        Double volumeSoFarRatio,
        double currentPrice,
        double actualFinalClose,
        double remainingDriftPct
) {
}
