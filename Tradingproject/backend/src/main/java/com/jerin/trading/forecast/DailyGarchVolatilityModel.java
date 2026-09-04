package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * GARCH(1,1) — but honestly: GARCH forecasts conditional *volatility*, not price direction, so
 * unlike every other {@link DailyForecastModel} candidate here it doesn't try to out-predict the
 * point value at all. Predicted close = today's open (zero drift, identical to
 * {@link DailyRandomWalkModel}) — the real test this model exists to run is whether its
 * volatility forecast produces a better-calibrated *range* than the fixed-ATR range every other
 * model uses, via {@link DailyForecastBacktestService}'s existing pctActualWithinPredictedRange
 * / avgRangeWidthPct metrics. Its meanAbsoluteErrorPct will therefore always exactly match
 * DAILY_RANDOM_WALK's — that's expected, not a bug, since the point prediction is identical by
 * design.
 *
 * Fits ω (variance-targeted, not free — see below), α, β at every prediction using only returns
 * through yesterday's close (no lookahead): variance targeting fixes ω from the sample long-run
 * variance so only a coarse grid search over (α, β) is needed, rather than a general nonlinear
 * MLE optimizer — a standard, legitimate GARCH simplification, not a shortcut that breaks
 * correctness. {@link DailyForecastBacktestService} auto-discovers this like every other
 * {@link DailyForecastModel} bean.
 */
@Component
public class DailyGarchVolatilityModel implements DailyForecastModel {

    private static final int MIN_OBSERVATIONS = 60;
    private static final double[] ALPHA_GRID = {0.02, 0.05, 0.08, 0.12, 0.16, 0.20, 0.25};
    private static final double[] BETA_GRID = {0.60, 0.65, 0.70, 0.75, 0.80, 0.85, 0.90};

    @Override
    public String name() {
        return "DAILY_GARCH_VOLATILITY";
    }

    @Override
    public ForecastPrediction predictClose(int index, ForecastContext context) {
        if (index < 1) {
            return null;
        }
        List<OhlcvCandle> candles = context.candles();
        double[] returns = new double[index - 1];
        for (int i = 1; i < index; i++) {
            double prev = candles.get(i - 1).getClose().doubleValue();
            double curr = candles.get(i).getClose().doubleValue();
            returns[i - 1] = (curr - prev) / prev;
        }
        if (returns.length < MIN_OBSERVATIONS) {
            return null;
        }

        // fit()'s recursion already ends by processing yesterday's return, which produces
        // exactly today's one-step-ahead forecast variance — recomputing the recursion step
        // here would double-apply yesterday's return and forecast one day too far ahead.
        GarchParams params = fit(returns);
        double forecastVolatility = Math.sqrt(Math.max(params.lastVariance(), 0));

        BigDecimal open = candles.get(index).getOpen();
        BigDecimal rangeWidth = open.multiply(BigDecimal.valueOf(forecastVolatility)).setScale(4, RoundingMode.HALF_UP);
        return new ForecastPrediction(open, open.subtract(rangeWidth), open.add(rangeWidth));
    }

    private record GarchParams(double omega, double alpha, double beta, double lastVariance) {
    }

    private GarchParams fit(double[] returns) {
        int n = returns.length;
        double longRunVariance = variance(returns);

        double bestLogLikelihood = Double.NEGATIVE_INFINITY;
        double bestAlpha = ALPHA_GRID[0];
        double bestBeta = BETA_GRID[0];
        double bestOmega = longRunVariance * (1 - bestAlpha - bestBeta);
        double bestLastVariance = longRunVariance;

        for (double alpha : ALPHA_GRID) {
            for (double beta : BETA_GRID) {
                if (alpha + beta >= 0.999) {
                    continue; // non-stationary combination, skip
                }
                double omega = longRunVariance * (1 - alpha - beta);
                double logLikelihood = 0;
                double variance = longRunVariance;
                for (double r : returns) {
                    variance = omega + alpha * r * r + beta * variance;
                    if (variance <= 0) {
                        variance = 1e-10;
                    }
                    // Gaussian innovations, zero mean — standard GARCH assumption at this
                    // frequency, where the mean daily return is negligible next to volatility.
                    logLikelihood += -0.5 * (Math.log(2 * Math.PI * variance) + (r * r) / variance);
                }
                if (logLikelihood > bestLogLikelihood) {
                    bestLogLikelihood = logLikelihood;
                    bestAlpha = alpha;
                    bestBeta = beta;
                    bestOmega = omega;
                    bestLastVariance = variance;
                }
            }
        }

        return new GarchParams(bestOmega, bestAlpha, bestBeta, bestLastVariance);
    }

    private double variance(double[] values) {
        double mean = 0;
        for (double v : values) {
            mean += v;
        }
        mean /= values.length;
        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.max(sum / values.length, 1e-10);
    }
}
