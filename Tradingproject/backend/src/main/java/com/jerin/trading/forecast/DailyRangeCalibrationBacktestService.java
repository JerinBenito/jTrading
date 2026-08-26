package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Same as {@link RangeCalibrationBacktestService}, for the same-day open-to-close prediction instead of the hourly one. */
@Service
public class DailyRangeCalibrationBacktestService {

    private static final int ATR_PERIOD = 14;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;

    public DailyRangeCalibrationBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<RangeCalibrationBacktestResult> compare(Instrument instrument) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), SOURCE_INTERVAL);
        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);
        if (daily.size() < 30) {
            return List.of();
        }

        List<BigDecimal> atr14 = AtrCalculator.calculate(daily, ATR_PERIOD);

        Accumulator fixed = new Accumulator("DAILY_RANDOM_WALK_FIXED_ATR");
        Accumulator adaptive = new Accumulator("DAILY_RANDOM_WALK_ADAPTIVE_RANGE");
        double k = RangeCalibrator.DEFAULT_MULTIPLIER;

        for (int i = 1; i < daily.size(); i++) {
            BigDecimal atrValue = atr14.get(i - 1);
            if (atrValue == null) {
                continue;
            }
            double predicted = daily.get(i).getOpen().doubleValue();
            double atr = atrValue.doubleValue();
            double actual = daily.get(i).getClose().doubleValue();

            fixed.record(predicted, actual, atr, 1.0);
            adaptive.record(predicted, actual, atr, k);

            boolean covered = Math.abs(actual - predicted) <= k * atr;
            k = RangeCalibrator.update(k, covered);
        }

        return List.of(fixed.toResult(1.0), adaptive.toResult(k));
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

        void record(double predicted, double actual, double atr, double multiplier) {
            double errorPct = Math.abs(actual - predicted) / actual * 100;
            sumAbsErrorPct += errorPct;
            double width = multiplier * atr;
            if (Math.abs(actual - predicted) <= width) {
                withinRange++;
            }
            sumRangeWidthPct += (2 * width) / predicted * 100;
            n++;
        }

        RangeCalibrationBacktestResult toResult(double finalMultiplier) {
            if (n == 0) {
                return new RangeCalibrationBacktestResult(name, 0, null, null, null, null);
            }
            return new RangeCalibrationBacktestResult(
                    name,
                    n,
                    BigDecimal.valueOf(sumAbsErrorPct / n).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf((double) withinRange / n * 100).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(sumRangeWidthPct / n).setScale(4, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(finalMultiplier).setScale(4, RoundingMode.HALF_UP));
        }
    }
}
