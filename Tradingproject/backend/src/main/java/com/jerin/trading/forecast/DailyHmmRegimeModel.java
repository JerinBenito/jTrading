package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A 2-state Gaussian Hidden Markov Model over daily returns — genuine regime detection (calm
 * vs. volatile/directional), not a fixed indicator. At each prediction, refits via Baum-Welch
 * (EM) using only returns through yesterday's close (no lookahead), then forecasts today's
 * expected return as the transition-weighted blend of each regime's mean return, using the
 * filtered (not smoothed — smoothing would use future data) state probabilities as of
 * yesterday. Predicted close = today's open scaled by that expected return.
 *
 * {@link DailyForecastBacktestService} auto-discovers this like every other
 * {@link DailyForecastModel} bean and compares it against {@link DailyRandomWalkModel} on 2
 * years of real history. Not wired into any live prediction loop — whether it ever is depends
 * entirely on that result, same discipline that kept {@link DailyMomentumModel} and
 * {@link DailyKalmanFilterModel} backtest-only after they lost.
 */
@Component
public class DailyHmmRegimeModel implements DailyForecastModel {

    private static final int STATES = 2;
    private static final int EM_ITERATIONS = 15;
    private static final int MIN_OBSERVATIONS = 40;

    @Override
    public String name() {
        return "DAILY_HMM_REGIME";
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
        double[] returns = new double[index - 1];
        for (int i = 1; i < index; i++) {
            double prev = candles.get(i - 1).getClose().doubleValue();
            double curr = candles.get(i).getClose().doubleValue();
            returns[i - 1] = (curr - prev) / prev;
        }
        if (returns.length < MIN_OBSERVATIONS) {
            return null;
        }

        HmmParams params = fit(returns);
        double expectedReturn = forecastNextReturn(returns, params);

        BigDecimal open = candles.get(index).getOpen();
        BigDecimal predicted = open.multiply(BigDecimal.valueOf(1 + expectedReturn)).setScale(4, RoundingMode.HALF_UP);
        return new ForecastPrediction(predicted, predicted.subtract(atr), predicted.add(atr));
    }

    // ---- Baum-Welch (EM) for a 2-state Gaussian HMM, scaled forward-backward ----

    private record HmmParams(double[] mean, double[] variance, double[][] transition, double[] initial) {
    }

    private HmmParams fit(double[] obs) {
        int n = obs.length;
        double overallMean = mean(obs);
        double overallVar = variance(obs, overallMean);
        double sd = Math.sqrt(overallVar);
        // Data-driven starting guess (split around the mean) rather than an arbitrary fixed
        // one, so EM has a sensible place to start from regardless of instrument/scale.
        double[] stateMean = {overallMean - sd * 0.5, overallMean + sd * 0.5};
        double[] stateVar = {overallVar, overallVar};
        double[][] transition = {{0.9, 0.1}, {0.1, 0.9}};
        double[] initial = {0.5, 0.5};

        for (int iter = 0; iter < EM_ITERATIONS; iter++) {
            double[][] emission = emissionMatrix(obs, stateMean, stateVar);
            double[][] alpha = new double[n][STATES];
            double[] scale = new double[n];
            forward(obs, emission, transition, initial, alpha, scale);
            double[][] beta = new double[n][STATES];
            backward(emission, transition, scale, beta);

            double[][] gamma = new double[n][STATES];
            for (int t = 0; t < n; t++) {
                double norm = 0;
                for (int s = 0; s < STATES; s++) {
                    gamma[t][s] = alpha[t][s] * beta[t][s];
                    norm += gamma[t][s];
                }
                for (int s = 0; s < STATES; s++) {
                    gamma[t][s] = norm > 0 ? gamma[t][s] / norm : 1.0 / STATES;
                }
            }
            double[][][] xi = new double[n - 1][STATES][STATES];
            for (int t = 0; t < n - 1; t++) {
                double norm = 0;
                for (int i = 0; i < STATES; i++) {
                    for (int j = 0; j < STATES; j++) {
                        xi[t][i][j] = alpha[t][i] * transition[i][j] * emission[t + 1][j] * beta[t + 1][j];
                        norm += xi[t][i][j];
                    }
                }
                if (norm > 0) {
                    for (int i = 0; i < STATES; i++) {
                        for (int j = 0; j < STATES; j++) {
                            xi[t][i][j] /= norm;
                        }
                    }
                }
            }

            // M-step
            double[] newInitial = gamma[0].clone();
            double[][] newTransition = new double[STATES][STATES];
            for (int i = 0; i < STATES; i++) {
                double denom = 0;
                for (int t = 0; t < n - 1; t++) {
                    denom += gamma[t][i];
                }
                for (int j = 0; j < STATES; j++) {
                    double numer = 0;
                    for (int t = 0; t < n - 1; t++) {
                        numer += xi[t][i][j];
                    }
                    newTransition[i][j] = denom > 0 ? numer / denom : transition[i][j];
                }
            }
            double[] newMean = new double[STATES];
            double[] newVar = new double[STATES];
            for (int s = 0; s < STATES; s++) {
                double wSum = 0;
                double wObsSum = 0;
                for (int t = 0; t < n; t++) {
                    wSum += gamma[t][s];
                    wObsSum += gamma[t][s] * obs[t];
                }
                newMean[s] = wSum > 0 ? wObsSum / wSum : stateMean[s];
                double wVarSum = 0;
                for (int t = 0; t < n; t++) {
                    double d = obs[t] - newMean[s];
                    wVarSum += gamma[t][s] * d * d;
                }
                newVar[s] = wSum > 0 ? Math.max(wVarSum / wSum, 1e-10) : stateVar[s];
            }

            stateMean = newMean;
            stateVar = newVar;
            transition = newTransition;
            initial = newInitial;
        }

        return new HmmParams(stateMean, stateVar, transition, initial);
    }

    /** One-step-ahead forecast: filtered state distribution as of the last observation,
     * projected forward one step through the transition matrix, then blended by each state's
     * mean return — the standard HMM forecast, using only data already observed. */
    private double forecastNextReturn(double[] obs, HmmParams params) {
        int n = obs.length;
        double[][] emission = emissionMatrix(obs, params.mean(), params.variance());
        double[][] alpha = new double[n][STATES];
        double[] scale = new double[n];
        forward(obs, emission, params.transition(), params.initial(), alpha, scale);

        double[] filtered = alpha[n - 1];
        double[] projected = new double[STATES];
        for (int j = 0; j < STATES; j++) {
            double p = 0;
            for (int i = 0; i < STATES; i++) {
                p += filtered[i] * params.transition()[i][j];
            }
            projected[j] = p;
        }

        double expected = 0;
        for (int s = 0; s < STATES; s++) {
            expected += projected[s] * params.mean()[s];
        }
        return expected;
    }

    private double[][] emissionMatrix(double[] obs, double[] mean, double[] variance) {
        double[][] emission = new double[obs.length][STATES];
        for (int t = 0; t < obs.length; t++) {
            for (int s = 0; s < STATES; s++) {
                emission[t][s] = gaussianPdf(obs[t], mean[s], variance[s]);
            }
        }
        return emission;
    }

    private void forward(double[] obs, double[][] emission, double[][] transition, double[] initial,
                          double[][] alpha, double[] scale) {
        int n = obs.length;
        for (int s = 0; s < STATES; s++) {
            alpha[0][s] = initial[s] * emission[0][s];
        }
        scale[0] = normalize(alpha[0]);
        for (int t = 1; t < n; t++) {
            for (int j = 0; j < STATES; j++) {
                double sum = 0;
                for (int i = 0; i < STATES; i++) {
                    sum += alpha[t - 1][i] * transition[i][j];
                }
                alpha[t][j] = sum * emission[t][j];
            }
            scale[t] = normalize(alpha[t]);
        }
    }

    private void backward(double[][] emission, double[][] transition, double[] scale, double[][] beta) {
        int n = emission.length;
        for (int s = 0; s < STATES; s++) {
            beta[n - 1][s] = 1.0;
        }
        for (int t = n - 2; t >= 0; t--) {
            for (int i = 0; i < STATES; i++) {
                double sum = 0;
                for (int j = 0; j < STATES; j++) {
                    sum += transition[i][j] * emission[t + 1][j] * beta[t + 1][j];
                }
                beta[t][i] = sum / scale[t + 1];
            }
        }
    }

    /** Normalizes in place (so scaled forward-backward doesn't underflow over ~500 steps) and
     * returns the pre-normalization sum, i.e. the scale factor. */
    private double normalize(double[] vector) {
        double sum = 0;
        for (double v : vector) {
            sum += v;
        }
        if (sum <= 0) {
            sum = 1e-300; // guards total underflow — leaves this step's alpha effectively zero, harmless
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= sum;
        }
        return sum;
    }

    private double gaussianPdf(double x, double mean, double variance) {
        double d = x - mean;
        return Math.exp(-(d * d) / (2 * variance)) / Math.sqrt(2 * Math.PI * variance);
    }

    private double mean(double[] values) {
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private double variance(double[] values, double mean) {
        double sum = 0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.max(sum / values.length, 1e-10);
    }
}
