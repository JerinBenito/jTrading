package com.jerin.trading.forecast;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Carries forward a damped fraction of yesterday's EMA9-EMA21 spread as expected drift on top
 * of today's open, same 25% damping constant and same rationale as {@link MomentumForecastModel}
 * — kept intentionally modest rather than fit to this (smaller, ~500-row) daily dataset. The
 * backtest decides whether this actually beats {@link DailyRandomWalkModel} before either is
 * trusted live, exactly as it did for the hourly loop (where momentum lost).
 */
@Component
public class DailyMomentumModel implements DailyForecastModel {

    private static final BigDecimal DAMPENING = BigDecimal.valueOf(0.25);

    @Override
    public String name() {
        return "DAILY_MOMENTUM_EMA_SPREAD";
    }

    @Override
    public ForecastPrediction predictClose(int index, ForecastContext context) {
        if (index < 1) {
            return null;
        }
        BigDecimal ema9 = context.ema9().get(index - 1);
        BigDecimal ema21 = context.ema21().get(index - 1);
        BigDecimal atr = context.atr14().get(index - 1);
        if (ema9 == null || ema21 == null || atr == null) {
            return null;
        }
        BigDecimal open = context.candles().get(index).getOpen();
        BigDecimal drift = ema9.subtract(ema21).multiply(DAMPENING).setScale(4, RoundingMode.HALF_UP);
        BigDecimal predicted = open.add(drift);
        return new ForecastPrediction(predicted, predicted.subtract(atr), predicted.add(atr));
    }
}
