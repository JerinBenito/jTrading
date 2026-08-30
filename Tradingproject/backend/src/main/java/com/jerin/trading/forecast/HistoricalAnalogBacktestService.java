package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
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
 * Walk-forward validation of {@link HistoricalAnalogCalculator} against 2 years of real
 * history: at each hour of each trading day, compares the plain volatility-scaled re-anchor
 * ({@link IntradayReanchorCalculator}, zero further drift assumed) against the historical-analog
 * estimate (informed by how similar past days — matched by intraday return-so-far at the same
 * hour checkpoint — actually finished). No lookahead: the historical pool for day i only ever
 * contains days strictly before i, and the range width uses only day i-1's ATR (never day i's
 * own high/low). Both strategies use the identical range width, so the comparison isolates the
 * point-estimate difference specifically — the historical-analog idea, not a wider/narrower band.
 */
@Service
public class HistoricalAnalogBacktestService {

    private static final int ATR_PERIOD = 14;
    private static final long NOMINAL_SESSION_MINUTES = 375;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;

    public HistoricalAnalogBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<HistoricalAnalogHourResult> compare(String instrumentTag) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<List<OhlcvCandle>> days = DailyBarAggregator.groupByDay(hourly);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourly);
        List<BigDecimal> dailyAtr14 = AtrCalculator.calculate(dailyBars, ATR_PERIOD);

        Map<Integer, HourAccumulator> baselineByHour = new TreeMap<>();
        Map<Integer, HourAccumulator> analogByHour = new TreeMap<>();
        Map<Integer, PoolSizeTracker> poolSizeByHour = new TreeMap<>();

        // (returnSoFar, remainingDrift) pairs, one list per hour-of-session index, built up day by day
        Map<Integer, List<ReturnDriftPair>> historicalPoolByHour = new TreeMap<>();

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
            double dayOpen = today.get(0).getOpen().doubleValue();
            double actualFinalClose = today.get(today.size() - 1).getClose().doubleValue();

            for (int h = 0; h < today.size(); h++) {
                OhlcvCandle candle = today.get(h);
                double currentPrice = candle.getClose().doubleValue();
                long elapsedMinutes = Duration.between(today.get(0).getTs(), candle.getTs()).toMinutes();
                double remaining = IntradayReanchorCalculator.remainingFraction(elapsedMinutes, NOMINAL_SESSION_MINUTES);
                double width = IntradayReanchorCalculator.remainingRangeWidth(atr, remaining);

                double todayReturnSoFar = (currentPrice - dayOpen) / dayOpen;
                List<ReturnDriftPair> pool = historicalPoolByHour.computeIfAbsent(h, k -> new ArrayList<>());

                Double drift = HistoricalAnalogCalculator.estimateRemainingDrift(pool, todayReturnSoFar);
                double analogPredicted = drift == null ? currentPrice : currentPrice * (1 + drift);

                baselineByHour.computeIfAbsent(h, k -> new HourAccumulator())
                        .record(currentPrice, actualFinalClose, width);
                analogByHour.computeIfAbsent(h, k -> new HourAccumulator())
                        .record(analogPredicted, actualFinalClose, width);
                poolSizeByHour.computeIfAbsent(h, k -> new PoolSizeTracker()).record(pool.size());

                // this day's own (returnSoFar, remainingDrift) at hour h joins the pool for future days only
                double remainingDriftToday = (actualFinalClose - currentPrice) / currentPrice;
                pool.add(new ReturnDriftPair(todayReturnSoFar, remainingDriftToday));
            }
        }

        List<HistoricalAnalogHourResult> results = new ArrayList<>();
        for (int h : baselineByHour.keySet()) {
            HourAccumulator baseline = baselineByHour.get(h);
            HourAccumulator analog = analogByHour.get(h);
            if (baseline.n == 0) {
                continue;
            }
            results.add(new HistoricalAnalogHourResult(
                    h, baseline.n, poolSizeByHour.get(h).average(),
                    baseline.meanAbsErrorPct(), baseline.coveragePct(),
                    analog.meanAbsErrorPct(), analog.coveragePct(),
                    baseline.avgRangeWidthPct()));
        }
        return results;
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

    private static final class PoolSizeTracker {
        private long sum = 0;
        private int n = 0;

        void record(int poolSize) {
            sum += poolSize;
            n++;
        }

        double average() {
            return n == 0 ? 0 : (double) sum / n;
        }
    }
}
