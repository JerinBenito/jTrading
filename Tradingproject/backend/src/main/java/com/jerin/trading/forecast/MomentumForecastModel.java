package com.jerin.trading.forecast;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Carries forward a damped fraction of the current EMA9-EMA21 spread as expected drift,
 * on top of the random-walk baseline. DAMPENING is a deliberately modest constant (we're
 * not trying to overfit a small dataset) — the backtest will show whether this beats
 * RandomWalkForecastModel at all before it's trusted for anything live.
 */
@Component
public class MomentumForecastModel implements ForecastModel {

    private static final BigDecimal DAMPENING = BigDecimal.valueOf(0.25);

    @Override
    public String name() {
        return "MOMENTUM_EMA_SPREAD";
    }

    @Override
    public ForecastPrediction predictNext(int index, ForecastContext context) {
        BigDecimal close = context.candles().get(index).getClose();
        BigDecimal ema9 = context.ema9().get(index);
        BigDecimal ema21 = context.ema21().get(index);
        BigDecimal atr = context.atr14().get(index);
        if (ema9 == null || ema21 == null || atr == null) {
            return null;
        }
        BigDecimal drift = ema9.subtract(ema21).multiply(DAMPENING).setScale(4, RoundingMode.HALF_UP);
        BigDecimal predicted = close.add(drift);
        return new ForecastPrediction(predicted, predicted.subtract(atr), predicted.add(atr));
    }
}
