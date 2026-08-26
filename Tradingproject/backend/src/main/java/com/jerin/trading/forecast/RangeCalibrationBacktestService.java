package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Walk-forward comparison of the current fixed {@code ±1×ATR} hourly range against
 * {@link RangeCalibrator}'s online-adaptive multiplier, on the {@code RANDOM_WALK} point
 * prediction (the model actually live). No lookahead: at each bar, the multiplier used is
 * whatever it evolved to from only the *prior* bars' outcomes — same online update the live
 * service would apply one evaluation at a time.
 */
@Service
public class RangeCalibrationBacktestService {

    private static final int ATR_PERIOD = 14;

    private final OhlcvCandleRepository candleRepository;

    public RangeCalibrationBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<RangeCalibrationBacktestResult> compare(Instrument instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), interval);
        if (candles.size() < 30) {
            return List.of();
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> atr14 = AtrCalculator.calculate(candles, ATR_PERIOD);

        Accumulator fixed = new Accumulator("RANDOM_WALK_FIXED_ATR");
        Accumulator adaptive = new Accumulator("RANDOM_WALK_ADAPTIVE_RANGE");
        double k = RangeCalibrator.DEFAULT_MULTIPLIER;

        for (int i = 0; i < candles.size() - 1; i++) {
            BigDecimal atrValue = atr14.get(i);
            if (atrValue == null) {
                continue;
            }
            double predicted = closes.get(i).doubleValue();
            double atr = atrValue.doubleValue();
            double actual = closes.get(i + 1).doubleValue();

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
