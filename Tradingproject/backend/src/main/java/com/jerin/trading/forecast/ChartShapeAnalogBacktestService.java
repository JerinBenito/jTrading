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
 * Answers a specific question the indicator-based analog tests (return-so-far, volatility, RSI,
 * EMA spread — see {@link RichHistoricalAnalogBacktestService}) never actually asked: does the
 * literal *shape* of the candles matter, not just derived indicator values? Adds three
 * candlestick-shape features to the existing 4-feature vector — body dominance (signed:
 * (close-open)/range), upper wick proportion, lower wick proportion — plus a short-term
 * consistency feature (how many of the last 3 candles closed up). Same walk-forward discipline
 * as every other analog backtest: the historical pool for day i only ever contains days strictly
 * before i.
 */
@Service
public class ChartShapeAnalogBacktestService {

    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int EMA_SHORT_PERIOD = 9;
    private static final int EMA_LONG_PERIOD = 21;
    private static final long NOMINAL_SESSION_MINUTES = 375;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;

    public ChartShapeAnalogBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<ChartShapeAnalogHourResult> compare(String instrumentTag) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<BigDecimal> closes = hourly.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> rsi14 = RsiCalculator.calculate(closes, RSI_PERIOD);
        List<BigDecimal> ema9 = EmaCalculator.calculate(closes, EMA_SHORT_PERIOD);
        List<BigDecimal> ema21 = EmaCalculator.calculate(closes, EMA_LONG_PERIOD);

        List<List<OhlcvCandle>> days = DailyBarAggregator.groupByDay(hourly);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourly);
        List<BigDecimal> dailyAtr14 = AtrCalculator.calculate(dailyBars, ATR_PERIOD);

        Map<Integer, HourAccumulator> baselineByHour = new TreeMap<>();
        Map<Integer, HourAccumulator> indicatorByHour = new TreeMap<>();
        Map<Integer, HourAccumulator> shapeByHour = new TreeMap<>();
        Map<Integer, PoolSizeTracker> poolSizeByHour = new TreeMap<>();
        Map<Integer, List<HistoricalDayObservation>> indicatorPoolByHour = new TreeMap<>();
        Map<Integer, List<HistoricalDayObservation>> shapePoolByHour = new TreeMap<>();

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

                BigDecimal rsiVal = rsi14.get(idx);
                BigDecimal ema9Val = ema9.get(idx);
                BigDecimal ema21Val = ema21.get(idx);
                boolean haveIndicators = rsiVal != null && ema9Val != null && ema21Val != null;

                List<HistoricalDayObservation> indicatorPool = indicatorPoolByHour.computeIfAbsent(h, k -> new ArrayList<>());
                List<HistoricalDayObservation> shapePool = shapePoolByHour.computeIfAbsent(h, k -> new ArrayList<>());

                double indicatorPredicted = currentPrice;
                double shapePredicted = currentPrice;

                if (haveIndicators) {
                    double emaSpreadPct = ema9Val.subtract(ema21Val).doubleValue() / currentPrice * 100;
                    double[] indicatorFeatures = {returnSoFar, volatilitySoFar, rsiVal.doubleValue(), emaSpreadPct};

                    Double indicatorDrift = MultiFeatureAnalogCalculator.estimateRemainingDrift(indicatorPool, indicatorFeatures);
                    if (indicatorDrift != null) {
                        indicatorPredicted = currentPrice * (1 + indicatorDrift);
                    }

                    double[] shapeFeatures = candleShapeFeatures(today, h, indicatorFeatures);
                    Double shapeDrift = MultiFeatureAnalogCalculator.estimateRemainingDrift(shapePool, shapeFeatures);
                    if (shapeDrift != null) {
                        shapePredicted = currentPrice * (1 + shapeDrift);
                    }

                    indicatorPool.add(new HistoricalDayObservation(indicatorFeatures, remainingDriftToday));
                    shapePool.add(new HistoricalDayObservation(shapeFeatures, remainingDriftToday));
                }

                baselineByHour.computeIfAbsent(h, k -> new HourAccumulator()).record(currentPrice, actualFinalClose, width);
                indicatorByHour.computeIfAbsent(h, k -> new HourAccumulator()).record(indicatorPredicted, actualFinalClose, width);
                shapeByHour.computeIfAbsent(h, k -> new HourAccumulator()).record(shapePredicted, actualFinalClose, width);
                poolSizeByHour.computeIfAbsent(h, k -> new PoolSizeTracker()).record(shapePool.size());
            }
            globalIndex += today.size();
        }

        List<ChartShapeAnalogHourResult> results = new ArrayList<>();
        for (int h : baselineByHour.keySet()) {
            HourAccumulator baseline = baselineByHour.get(h);
            HourAccumulator indicator = indicatorByHour.get(h);
            HourAccumulator shape = shapeByHour.get(h);
            if (baseline.n == 0) {
                continue;
            }
            results.add(new ChartShapeAnalogHourResult(
                    h, baseline.n, poolSizeByHour.get(h).average(),
                    baseline.meanAbsErrorPct(), baseline.coveragePct(),
                    indicator.meanAbsErrorPct(), indicator.coveragePct(),
                    shape.meanAbsErrorPct(), shape.coveragePct(),
                    baseline.avgRangeWidthPct()));
        }
        return results;
    }

    /** [returnSoFar, volatilitySoFar, rsi14, emaSpreadPct, bodyPct, upperWickPct, lowerWickPct, last3UpCount]. */
    private double[] candleShapeFeatures(List<OhlcvCandle> today, int h, double[] indicatorFeatures) {
        OhlcvCandle candle = today.get(h);
        double open = candle.getOpen().doubleValue();
        double high = candle.getHigh().doubleValue();
        double low = candle.getLow().doubleValue();
        double close = candle.getClose().doubleValue();
        double range = high - low;

        double bodyPct = range > 1e-9 ? (close - open) / range : 0.0;
        double upperWickPct = range > 1e-9 ? (high - Math.max(open, close)) / range : 0.0;
        double lowerWickPct = range > 1e-9 ? (Math.min(open, close) - low) / range : 0.0;

        int windowStart = Math.max(0, h - 2);
        int upCount = 0;
        for (int j = windowStart; j <= h; j++) {
            OhlcvCandle c = today.get(j);
            if (c.getClose().compareTo(c.getOpen()) > 0) {
                upCount++;
            }
        }

        return new double[]{
                indicatorFeatures[0], indicatorFeatures[1], indicatorFeatures[2], indicatorFeatures[3],
                bodyPct, upperWickPct, lowerWickPct, upCount
        };
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
