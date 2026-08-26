package com.jerin.trading.forecast;

/**
 * Predicts a *same-day* open-to-close move, unlike {@link ForecastModel} which predicts the
 * *next* bar from the current one. Given today's daily bar at `index`, only its open is known
 * at prediction time — high/low/close are still in progress — so implementations must only
 * read indicator values through `index - 1` (yesterday and earlier) plus `candles.get(index).getOpen()`.
 * Reading `candles.get(index)`'s high/low/close, or `atr14`/`ema9`/`ema21` at `index` itself,
 * would leak information not actually available at market open.
 */
public interface DailyForecastModel {

    String name();

    /** @param context daily bars + indicators, see {@link ForecastContext} — same shape, daily granularity */
    ForecastPrediction predictClose(int index, ForecastContext context);
}
