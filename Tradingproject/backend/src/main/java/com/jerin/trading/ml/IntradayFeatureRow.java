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
        double currentPrice,
        double actualFinalClose,
        double remainingDriftPct
) {
}
