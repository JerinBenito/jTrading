package com.jerin.trading.forecast;

/**
 * One historical day's state at a given hour checkpoint: how far it had already moved from its
 * own open ({@code returnSoFar}), and how much further it moved from that checkpoint to its own
 * close ({@code remainingDrift}) — the raw material for {@link HistoricalAnalogCalculator}.
 */
public record ReturnDriftPair(double returnSoFar, double remainingDrift) {
}
