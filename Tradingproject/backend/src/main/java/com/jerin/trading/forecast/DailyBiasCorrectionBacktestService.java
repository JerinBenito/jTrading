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
 * Same validation as {@link BiasCorrectionBacktestService}, applied to the same-day
 * open-to-close prediction instead of the hourly one — don't assume the hourly finding
 * (gated correction ~= no correction, naive averaging actively hurts) transfers to a
 * different granularity and a much smaller sample (~500 trading days vs ~3500 hourly bars)
 * without checking. Base prediction is the daily random-walk baseline (today's open); each
 * correction strategy's rolling error window is built sequentially from its own past
 * predictions only, no lookahead.
 */
@Service
public class DailyBiasCorrectionBacktestService {

    private static final int NAIVE_WINDOW = 20;
    private static final int GATED_WINDOW = 60;
    private static final int ATR_PERIOD = 14;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;

    public DailyBiasCorrectionBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<ForecastBacktestResult> compare(Instrument instrument) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), SOURCE_INTERVAL);
        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);
        if (daily.size() < 60) {
            return List.of();
        }

        List<BigDecimal> atr14 = AtrCalculator.calculate(daily, ATR_PERIOD);

        Accumulator pure = new Accumulator("DAILY_RANDOM_WALK");
        Accumulator naive = new Accumulator("DAILY_BIAS_CORRECTED_NAIVE_20AVG");
        Accumulator gated = new Accumulator("DAILY_BIAS_CORRECTED_SIGNIFICANCE_GATED");

        List<Double> naiveErrors = new ArrayList<>();
        List<Double> gatedErrors = new ArrayList<>();

        for (int i = 1; i < daily.size(); i++) {
            BigDecimal atrValue = atr14.get(i - 1);
            if (atrValue == null) {
                continue;
            }
            double open = daily.get(i).getOpen().doubleValue();
            double atr = atrValue.doubleValue();
            double actual = daily.get(i).getClose().doubleValue();

            pure.record(open, actual, atr);

            double naiveCorrection = naiveErrors.size() < NAIVE_WINDOW ? 0.0
                    : average(naiveErrors.subList(naiveErrors.size() - NAIVE_WINDOW, naiveErrors.size()));
            double naivePrediction = open + naiveCorrection;
            naive.record(naivePrediction, actual, atr);
            naiveErrors.add(actual - naivePrediction);

            List<Double> gatedWindow = gatedErrors.size() > GATED_WINDOW
                    ? gatedErrors.subList(gatedErrors.size() - GATED_WINDOW, gatedErrors.size())
                    : gatedErrors;
            double gatedCorrection = BiasCorrectionCalculator.calculate(gatedWindow).doubleValue();
            double gatedPrediction = open + gatedCorrection;
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
