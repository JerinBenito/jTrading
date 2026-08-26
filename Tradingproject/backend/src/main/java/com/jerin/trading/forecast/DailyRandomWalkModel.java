package com.jerin.trading.forecast;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The honest same-day baseline: today's close is predicted to equal today's open (zero
 * drift), ranged by yesterday's ATR14 — the last full day's volatility is the best estimate
 * of today's available at market open, before today's own high/low exist. Mirrors
 * {@link RandomWalkForecastModel}'s role for the hourly loop: the bar any fancier model has
 * to actually beat, not assumed inferior.
 */
@Component
public class DailyRandomWalkModel implements DailyForecastModel {

    @Override
    public String name() {
        return "DAILY_RANDOM_WALK";
    }

    @Override
    public ForecastPrediction predictClose(int index, ForecastContext context) {
        if (index < 1) {
            return null;
        }
        BigDecimal atr = context.atr14().get(index - 1);
        if (atr == null) {
            return null;
        }
        BigDecimal open = context.candles().get(index).getOpen();
        return new ForecastPrediction(open, open.subtract(atr), open.add(atr));
    }
}
