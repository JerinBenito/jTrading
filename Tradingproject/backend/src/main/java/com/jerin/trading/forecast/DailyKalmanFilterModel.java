package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A local-level Kalman filter: treats each day's close as a noisy observation of a slowly
 * evolving "true" price level, and recursively re-weights each new observation against the
 * prior belief using the statistically optimal (minimum-variance) gain, rather than a fixed
 * smoothing constant like an EMA. Predicted close = today's open plus whatever gap the filter
 * believes existed between yesterday's raw close and its smoothed level — same "carry a learned
 * correction forward onto today's open" shape as {@link DailyMomentumModel}, just derived from a
 * state-space filter instead of an EMA spread.
 *
 * {@link DailyForecastBacktestService} auto-discovers every {@link DailyForecastModel} bean and
 * compares it against {@link DailyRandomWalkModel} on 2 years of real history — this model is
 * NOT wired into any live prediction loop. Whether it's ever promoted to one depends entirely on
 * that backtest result, same discipline that kept {@link DailyMomentumModel} backtest-only after
 * it lost to random walk.
 */
@Component
public class DailyKalmanFilterModel implements DailyForecastModel {

    // A ratio, not absolute variances — a local-level filter's gain only depends on the ratio
    // of process to measurement variance, so this stays dimensionless and doesn't need
    // recalibrating between NIFTY (~24000) and a ₹500 basket stock. Picked as a fixed,
    // moderate value rather than fit to this dataset, same discipline as DailyMomentumModel's
    // fixed dampening constant — the backtest decides if it's any good, not a grid search.
    private static final double PROCESS_TO_MEASUREMENT_VARIANCE_RATIO = 0.15;

    @Override
    public String name() {
        return "DAILY_KALMAN_FILTER";
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

        List<OhlcvCandle> candles = context.candles();
        double level = candles.get(0).getClose().doubleValue();
        double variance = 1.0; // arbitrary starting uncertainty — washes out after a few updates

        for (int i = 1; i <= index - 1; i++) {
            double observation = candles.get(i).getClose().doubleValue();
            // Predict step: transition is identity (local level), so the level forecast is
            // unchanged; only uncertainty grows, by the process variance.
            double predictedVariance = variance + PROCESS_TO_MEASUREMENT_VARIANCE_RATIO;
            // Update step: blend prediction and observation by the Kalman gain — the
            // statistically optimal weighting given how much each is currently trusted.
            double gain = predictedVariance / (predictedVariance + 1.0);
            level += gain * (observation - level);
            variance = (1 - gain) * predictedVariance;
        }

        BigDecimal lastClose = candles.get(index - 1).getClose();
        BigDecimal filteredCorrection = BigDecimal.valueOf(level).subtract(lastClose);
        BigDecimal open = candles.get(index).getOpen();
        BigDecimal predicted = open.add(filteredCorrection).setScale(4, RoundingMode.HALF_UP);
        return new ForecastPrediction(predicted, predicted.subtract(atr), predicted.add(atr));
    }
}
