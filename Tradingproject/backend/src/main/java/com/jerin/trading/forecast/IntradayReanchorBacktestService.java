package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Walk-forward validation of {@link IntradayReanchorCalculator} against 2 years of real history
 * — no lookahead: for each trading day, "static" always means the prediction made from that
 * day's own opening candle (exactly what's live today), and "re-anchored" at hour h only ever
 * uses that day's candles up to and including hour h. Both are scored against the same day's
 * actual final close. Also isolates the specific scenario asked about: when the ~10:15 IST
 * price already breaks outside the static range, does re-anchoring there actually help by the
 * close, or is it a coin flip?
 */
@Service
public class IntradayReanchorBacktestService {

    private static final int ATR_PERIOD = 14;
    private static final String SOURCE_INTERVAL = "1h";
    private static final double BIG_MISS_ATR_MULTIPLE = 1.0;
    private static final int BIG_MISS_HOUR_INDEX = 1; // the 2nd candle of the day (~10:15 IST for a 9:15 open)

    private final OhlcvCandleRepository candleRepository;

    public IntradayReanchorBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public IntradayReanchorBacktestResult compare(Instrument instrument) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), SOURCE_INTERVAL);
        List<List<OhlcvCandle>> days = DailyBarAggregator.groupByDay(hourly);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourly);
        List<BigDecimal> dailyAtr14 = AtrCalculator.calculate(dailyBars, ATR_PERIOD);

        Map<Integer, HourAccumulator> staticByHour = new TreeMap<>();
        Map<Integer, HourAccumulator> reanchoredByHour = new TreeMap<>();
        HourAccumulator bigMissStatic = new HourAccumulator();
        HourAccumulator bigMissReanchored = new HourAccumulator();

        for (int i = 1; i < days.size(); i++) {
            List<OhlcvCandle> today = days.get(i);
            if (today.size() < 2) {
                continue;
            }
            BigDecimal atrValue = dailyAtr14.get(i - 1);
            if (atrValue == null) {
                continue;
            }
            double atr = atrValue.doubleValue();
            double staticPrediction = today.get(0).getOpen().doubleValue();
            double actualFinalClose = today.get(today.size() - 1).getClose().doubleValue();
            long totalSessionMinutes = Duration.between(today.get(0).getTs(), today.get(today.size() - 1).getTs()).toMinutes();

            for (int h = 0; h < today.size(); h++) {
                OhlcvCandle candle = today.get(h);
                double currentPrice = candle.getClose().doubleValue();
                long elapsedMinutes = Duration.between(today.get(0).getTs(), candle.getTs()).toMinutes();
                double remaining = IntradayReanchorCalculator.remainingFraction(elapsedMinutes, totalSessionMinutes);
                double reanchoredWidth = IntradayReanchorCalculator.remainingRangeWidth(atr, remaining);

                staticByHour.computeIfAbsent(h, k -> new HourAccumulator())
                        .record(staticPrediction, actualFinalClose, atr);
                reanchoredByHour.computeIfAbsent(h, k -> new HourAccumulator())
                        .record(currentPrice, actualFinalClose, reanchoredWidth);
            }

            if (today.size() > BIG_MISS_HOUR_INDEX) {
                OhlcvCandle checkpoint = today.get(BIG_MISS_HOUR_INDEX);
                double checkpointPrice = checkpoint.getClose().doubleValue();
                boolean bigMiss = Math.abs(checkpointPrice - staticPrediction) > BIG_MISS_ATR_MULTIPLE * atr;
                if (bigMiss) {
                    long elapsedMinutes = Duration.between(today.get(0).getTs(), checkpoint.getTs()).toMinutes();
                    double remaining = IntradayReanchorCalculator.remainingFraction(elapsedMinutes, totalSessionMinutes);
                    double reanchoredWidth = IntradayReanchorCalculator.remainingRangeWidth(atr, remaining);

                    bigMissStatic.record(staticPrediction, actualFinalClose, atr);
                    bigMissReanchored.record(checkpointPrice, actualFinalClose, reanchoredWidth);
                }
            }
        }

        List<HourBucketComparison> byHour = new ArrayList<>();
        for (int h : staticByHour.keySet()) {
            HourAccumulator s = staticByHour.get(h);
            HourAccumulator r = reanchoredByHour.get(h);
            if (s.n == 0) {
                continue;
            }
            byHour.add(new HourBucketComparison(
                    h, s.n,
                    s.meanAbsErrorPct(), s.coveragePct(),
                    r.meanAbsErrorPct(), r.coveragePct(), r.avgRangeWidthPct()));
        }

        BigMorningMissComparison bigMorningMiss = new BigMorningMissComparison(
                bigMissStatic.n,
                bigMissStatic.meanAbsErrorPct(), bigMissStatic.coveragePct(),
                bigMissReanchored.meanAbsErrorPct(), bigMissReanchored.coveragePct());

        return new IntradayReanchorBacktestResult(byHour, bigMorningMiss);
    }

    private static final class HourAccumulator {
        private int n = 0;
        private double sumAbsErrorPct = 0;
        private int withinRange = 0;
        private double sumRangeWidthPct = 0;

        void record(double predicted, double actualFinalClose, double rangeWidth) {
            double errorPct = Math.abs(actualFinalClose - predicted) / actualFinalClose * 100;
            sumAbsErrorPct += errorPct;
            if (Math.abs(actualFinalClose - predicted) <= rangeWidth) {
                withinRange++;
            }
            sumRangeWidthPct += (2 * rangeWidth) / predicted * 100;
            n++;
        }

        BigDecimal meanAbsErrorPct() {
            return n == 0 ? null : BigDecimal.valueOf(sumAbsErrorPct / n).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal coveragePct() {
            return n == 0 ? null : BigDecimal.valueOf((double) withinRange / n * 100).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal avgRangeWidthPct() {
            return n == 0 ? null : BigDecimal.valueOf(sumRangeWidthPct / n).setScale(4, RoundingMode.HALF_UP);
        }
    }
}
