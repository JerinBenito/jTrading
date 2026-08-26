package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Walk-forward validation of bias-correction *strategies* (not just base models) — simulates,
 * hour by hour through the full stored history, what each correction approach would actually
 * have predicted, using only data available at that point (each approach's own rolling error
 * window is built sequentially from its own past predictions, exactly mirroring how the live
 * system behaves — no lookahead). Exists specifically because the naive 20-sample-average
 * correction that went live 2026-08-21 was found (real production data, 2026-08-25) to be
 * making NIFTY predictions meaningfully worse — this validates a statistically-gated
 * replacement against 2 years of real history before trusting it live.
 */
@Service
public class BiasCorrectionBacktestService {

    private static final int NAIVE_WINDOW = 20;
    private static final int GATED_WINDOW = 50;
    private static final int ATR_PERIOD = 14;

    private final OhlcvCandleRepository candleRepository;

    public BiasCorrectionBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<ForecastBacktestResult> compare(Instrument instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), interval);
        if (candles.size() < 60) {
            return List.of();
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> atr14 = AtrCalculator.calculate(candles, ATR_PERIOD);

        Accumulator pure = new Accumulator("RANDOM_WALK");
        Accumulator naive = new Accumulator("BIAS_CORRECTED_NAIVE_20AVG");
        Accumulator gated = new Accumulator("BIAS_CORRECTED_SIGNIFICANCE_GATED");

        List<Double> naiveErrors = new ArrayList<>();
        List<Double> gatedErrors = new ArrayList<>();

        for (int i = 0; i < candles.size() - 1; i++) {
            BigDecimal atrValue = atr14.get(i);
            if (atrValue == null) {
                continue;
            }
            double base = closes.get(i).doubleValue();
            double atr = atrValue.doubleValue();
            double actual = closes.get(i + 1).doubleValue();

            pure.record(base, actual, atr);

            double naiveCorrection = naiveErrors.size() < NAIVE_WINDOW ? 0.0
                    : average(naiveErrors.subList(naiveErrors.size() - NAIVE_WINDOW, naiveErrors.size()));
            double naivePrediction = base + naiveCorrection;
            naive.record(naivePrediction, actual, atr);
            naiveErrors.add(actual - naivePrediction);

            List<Double> gatedWindow = gatedErrors.size() > GATED_WINDOW
                    ? gatedErrors.subList(gatedErrors.size() - GATED_WINDOW, gatedErrors.size())
                    : gatedErrors;
            double gatedCorrection = BiasCorrectionCalculator.calculate(gatedWindow).doubleValue();
            double gatedPrediction = base + gatedCorrection;
            gated.record(gatedPrediction, actual, atr);
            gatedErrors.add(actual - gatedPrediction);
        }

        return List.of(pure.toResult(), naive.toResult(), gated.toResult());
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static final class Accumulator {
        private final String name;
        private int n = 0;
        private double sumAbsErrorPct = 0;
        private int withinRange = 0;
        private double sumRangeWidthPct = 0;

        Accumulator(String name) {
            this.name = name;
        }

        void record(double predicted, double actual, double atr) {
            double errorPct = Math.abs(actual - predicted) / actual * 100;
            sumAbsErrorPct += errorPct;
            double low = predicted - atr;
            double high = predicted + atr;
            if (actual >= low && actual <= high) {
                withinRange++;
            }
            sumRangeWidthPct += (2 * atr) / predicted * 100;
            n++;
        }

        ForecastBacktestResult toResult() {
            if (n == 0) {
                return new ForecastBacktestResult(name, 0, null, null, null);
            }
            return new ForecastBacktestResult(
                    name,
                    n,
                    BigDecimal.valueOf(sumAbsErrorPct / n).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf((double) withinRange / n * 100).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(sumRangeWidthPct / n).setScale(4, RoundingMode.HALF_UP));
        }
    }
}
