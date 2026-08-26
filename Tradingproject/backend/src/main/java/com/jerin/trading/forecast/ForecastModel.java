package com.jerin.trading.forecast;

/**
 * Predicts the close of the bar at {@code index+1} using only data available at
 * {@code index} and earlier — no lookahead. Candidate models are hand-defined and compared
 * by backtest, same "no black-box" principle as the pattern library.
 */
public interface ForecastModel {

    String name();

    /** Null if there isn't enough data yet at this index to predict. */
    ForecastPrediction predictNext(int index, ForecastContext context);
}
