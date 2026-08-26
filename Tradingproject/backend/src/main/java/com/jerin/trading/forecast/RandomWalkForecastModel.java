package com.jerin.trading.forecast;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The honest baseline: next hour's close is predicted to be the same as this hour's close
 * (zero drift), with an ATR-based range. Financial prices are close to a random walk at short
 * horizons, so this is the bar any fancier model has to actually beat — not assumed inferior.
 */
@Component
public class RandomWalkForecastModel implements ForecastModel {

    @Override
    public String name() {
        return "RANDOM_WALK";
    }

    @Override
    public ForecastPrediction predictNext(int index, ForecastContext context) {
        BigDecimal close = context.candles().get(index).getClose();
        BigDecimal atr = context.atr14().get(index);
        if (atr == null) {
            return null;
        }
        return new ForecastPrediction(close, close.subtract(atr), close.add(atr));
    }
}
