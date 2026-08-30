package com.jerin.trading.forecast;

/**
 * One historical day's multi-dimensional state at a given hour checkpoint, plus how much
 * further it moved from that checkpoint to its own close — the raw material for
 * {@link MultiFeatureAnalogCalculator}. Feature order is fixed by whoever builds these
 * (see {@link RichHistoricalAnalogBacktestService}): [returnSoFar, volatilitySoFar, rsi14, emaSpreadPct].
 */
public record HistoricalDayObservation(double[] features, double remainingDrift) {
}
