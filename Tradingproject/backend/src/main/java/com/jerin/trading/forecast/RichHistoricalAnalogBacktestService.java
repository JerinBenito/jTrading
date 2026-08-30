package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
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
 * Same walk-forward discipline as {@link HistoricalAnalogBacktestService}, extended with a
 * third strategy: {@link MultiFeatureAnalogCalculator} matching on return-so-far,
 * volatility-so-far (today's own high-low range so far, normalized by open), RSI14, and the
 * EMA9-EMA21 spread — "similar morning move, similar volatility regime, similar indicator
 * state," per the original request, rather than return-so-far alone. RSI/EMA are computed once
 * over the full continuous hourly series (same rolling-indicator convention used everywhere
 * else in this codebase) and indexed back to each day's candles by position — never using a
 * value computed from data at or after the point being predicted.
 */
@Service
public class RichHistoricalAnalogBacktestService {

    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int EMA_SHORT_PERIOD = 9;
    private static final int EMA_LONG_PERIOD = 21;
    private static final long NOMINAL_SESSION_MINUTES = 375;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;

    public RichHistoricalAnalogBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<RichHistoricalAnalogHourResult> compare(String instrumentTag) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<BigDecimal> closes = hourly.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> rsi14 = RsiCalculator.calculate(closes, RSI_PERIOD);
        List<BigDecimal> ema9 = EmaCalculator.calculate(closes, EMA_SHORT_PERIOD);
        List<BigDecimal> ema21 = EmaCalculator.calculate(closes, EMA_LONG_PERIOD);

        List<List<OhlcvCandle>> days = DailyBarAggregator.groupByDay(hourly);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourly);
        List<BigDecimal> dailyAtr14 = AtrCalculator.calculate(dailyBars, ATR_PERIOD);

        Map<Integer, HourAccumulator> baselineByHour = new TreeMap<>();
        Map<Integer, HourAccumulator> simpleByHour = new TreeMap<>();
        Map<Integer, HourAccumulator> richByHour = new TreeMap<>();
        Map<Integer, PoolSizeTracker> poolSizeByHour = new TreeMap<>();
        Map<Integer, List<ReturnDriftPair>> simplePoolByHour = new TreeMap<>();
        Map<Integer, List<HistoricalDayObservation>> richPoolByHour = new TreeMap<>();

        int globalIndex = 0;
        for (int i = 1; i < days.size(); i++) {
            List<OhlcvCandle> today = days.get(i);
            if (today.size() < 2) {
                globalIndex += today.size();
                continue;
            }
            BigDecimal atrValue = dailyAtr14.get(i - 1);
            if (atrValue == null) {
                globalIndex += today.size();
                continue;
            }
            double atr = atrValue.doubleValue();
            double dayOpen = today.get(0).getOpen().doubleValue();
            double actualFinalClose = today.get(today.size() - 1).getClose().doubleValue();
            double runningHigh = Double.NEGATIVE_INFINITY;
            double runningLow = Double.POSITIVE_INFINITY;

            for (int h = 0; h < today.size(); h++) {
                OhlcvCandle candle = today.get(h);
                int idx = globalIndex + h;
                double currentPrice = candle.getClose().doubleValue();
                runningHigh = Math.max(runningHigh, candle.getHigh().doubleValue());
                runningLow = Math.min(runningLow, candle.getLow().doubleValue());

                long elapsedMinutes = Duration.between(today.get(0).getTs(), candle.getTs()).toMinutes();
                double remaining = IntradayReanchorCalculator.remainingFraction(elapsedMinutes, NOMINAL_SESSION_MINUTES);
                double width = IntradayReanchorCalculator.remainingRangeWidth(atr, remaining);

                double returnSoFar = (currentPrice - dayOpen) / dayOpen;
                double volatilitySoFar = (runningHigh - runningLow) / dayOpen;
                double remainingDriftToday = (actualFinalClose - currentPrice) / currentPrice;

                List<ReturnDriftPair> simplePool = simplePoolByHour.computeIfAbsent(h, k -> new ArrayList<>());
                List<HistoricalDayObservation> richPool = richPoolByHour.computeIfAbsent(h, k -> new ArrayList<>());

                Double simpleDrift = HistoricalAnalogCalculator.estimateRemainingDrift(simplePool, returnSoFar);
                double simplePredicted = simpleDrift == null ? currentPrice : currentPrice * (1 + simpleDrift);

                BigDecimal rsiVal = rsi14.get(idx);
                BigDecimal ema9Val = ema9.get(idx);
                BigDecimal ema21Val = ema21.get(idx);
                boolean haveIndicators = rsiVal != null && ema9Val != null && ema21Val != null;
                double[] todayFeatures = null;
                if (haveIndicators) {
                    double emaSpreadPct = ema9Val.subtract(ema21Val).doubleValue() / currentPrice * 100;
                    todayFeatures = new double[]{returnSoFar, volatilitySoFar, rsiVal.doubleValue(), emaSpreadPct};
                }

                double richPredicted = currentPrice;
                if (todayFeatures != null) {
                    Double richDrift = MultiFeatureAnalogCalculator.estimateRemainingDrift(richPool, todayFeatures);
                    if (richDrift != null) {
                        richPredicted = currentPrice * (1 + richDrift);
                    }
                }

                baselineByHour.computeIfAbsent(h, k -> new HourAccumulator()).record(currentPrice, actualFinalClose, width);
                simpleByHour.computeIfAbsent(h, k -> new HourAccumulator()).record(simplePredicted, actualFinalClose, width);
                richByHour.computeIfAbsent(h, k -> new HourAccumulator()).record(richPredicted, actualFinalClose, width);
                poolSizeByHour.computeIfAbsent(h, k -> new PoolSizeTracker()).record(richPool.size());

                simplePool.add(new ReturnDriftPair(returnSoFar, remainingDriftToday));
                if (todayFeatures != null) {
                    richPool.add(new HistoricalDayObservation(todayFeatures, remainingDriftToday));
                }
            }
            globalIndex += today.size();
        }

        List<RichHistoricalAnalogHourResult> results = new ArrayList<>();
        for (int h : baselineByHour.keySet()) {
            HourAccumulator baseline = baselineByHour.get(h);
            HourAccumulator simple = simpleByHour.get(h);
            HourAccumulator rich = richByHour.get(h);
            if (baseline.n == 0) {
                continue;
            }
            results.add(new RichHistoricalAnalogHourResult(
                    h, baseline.n, poolSizeByHour.get(h).average(),
                    baseline.meanAbsErrorPct(), baseline.coveragePct(),
                    simple.meanAbsErrorPct(), simple.coveragePct(),
                    rich.meanAbsErrorPct(), rich.coveragePct(),
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
