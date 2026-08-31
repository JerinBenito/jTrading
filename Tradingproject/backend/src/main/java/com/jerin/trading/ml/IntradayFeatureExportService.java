package com.jerin.trading.ml;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.forecast.DailyBarAggregator;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.ingestion.Nifty50Constituents;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Raw export of the same per-(day,hour) feature+outcome rows already computed transiently inside
 * {@link com.jerin.trading.forecast.ChartShapeAnalogBacktestService} for its k-NN test — exposed
 * here so an actual trainable model (gradient boosting, not a fixed neighbor-average) can be
 * tried against the identical feature set and identical historical days, offline. Read-only,
 * nothing persisted; purely a data export for external training/evaluation.
 */
@Service
public class IntradayFeatureExportService {

    private static final int ATR_PERIOD = 14;
    private static final int RSI_PERIOD = 14;
    private static final int EMA_SHORT_PERIOD = 9;
    private static final int EMA_LONG_PERIOD = 21;
    private static final int VOLUME_LOOKBACK_DAYS = 20;
    private static final String SOURCE_INTERVAL = "1h";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlcvCandleRepository candleRepository;

    public IntradayFeatureExportService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public List<IntradayFeatureRow> export(String instrumentTag) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<BigDecimal> closes = hourly.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> rsi14 = RsiCalculator.calculate(closes, RSI_PERIOD);
        List<BigDecimal> ema9 = EmaCalculator.calculate(closes, EMA_SHORT_PERIOD);
        List<BigDecimal> ema21 = EmaCalculator.calculate(closes, EMA_LONG_PERIOD);

        List<List<OhlcvCandle>> days = DailyBarAggregator.groupByDay(hourly);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourly);
        List<BigDecimal> dailyAtr14 = AtrCalculator.calculate(dailyBars, ATR_PERIOD);

        List<IntradayFeatureRow> rows = new ArrayList<>();
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
            double dayOpen = today.get(0).getOpen().doubleValue();
            double actualFinalClose = today.get(today.size() - 1).getClose().doubleValue();
            double runningHigh = Double.NEGATIVE_INFINITY;
            double runningLow = Double.POSITIVE_INFINITY;
            long volumeSoFar = 0;
            boolean volumeKnown = true;
            String tradingDate = today.get(0).getTs().atZoneSameInstant(IST).toLocalDate().toString();
            Double trailingAvgDailyVolume = trailingAvgVolume(dailyBars, i);

            for (int h = 0; h < today.size(); h++) {
                OhlcvCandle candle = today.get(h);
                int idx = globalIndex + h;
                double currentPrice = candle.getClose().doubleValue();
                runningHigh = Math.max(runningHigh, candle.getHigh().doubleValue());
                runningLow = Math.min(runningLow, candle.getLow().doubleValue());
                if (candle.getVolume() != null) {
                    volumeSoFar += candle.getVolume();
                } else {
                    volumeKnown = false;
                }
                Double volumeSoFarRatio = (volumeKnown && trailingAvgDailyVolume != null && trailingAvgDailyVolume > 0)
                        ? volumeSoFar / trailingAvgDailyVolume
                        : null;

                BigDecimal rsiVal = rsi14.get(idx);
                BigDecimal ema9Val = ema9.get(idx);
                BigDecimal ema21Val = ema21.get(idx);
                if (rsiVal == null || ema9Val == null || ema21Val == null) {
                    continue;
                }

                double returnSoFarPct = (currentPrice - dayOpen) / dayOpen * 100;
                double volatilitySoFarPct = (runningHigh - runningLow) / dayOpen * 100;
                double emaSpreadPct = ema9Val.subtract(ema21Val).doubleValue() / currentPrice * 100;
                double remainingDriftPct = (actualFinalClose - currentPrice) / currentPrice * 100;

                double open = candle.getOpen().doubleValue();
                double high = candle.getHigh().doubleValue();
                double low = candle.getLow().doubleValue();
                double range = high - low;
                double bodyPct = range > 1e-9 ? (currentPrice - open) / range * 100 : 0.0;
                double upperWickPct = range > 1e-9 ? (high - Math.max(open, currentPrice)) / range * 100 : 0.0;
                double lowerWickPct = range > 1e-9 ? (Math.min(open, currentPrice) - low) / range * 100 : 0.0;

                int windowStart = Math.max(0, h - 2);
                int upCount = 0;
                for (int j = windowStart; j <= h; j++) {
                    OhlcvCandle c = today.get(j);
                    if (c.getClose().compareTo(c.getOpen()) > 0) {
                        upCount++;
                    }
                }

                rows.add(new IntradayFeatureRow(
                        instrumentTag, tradingDate, h,
                        returnSoFarPct, volatilitySoFarPct, rsiVal.doubleValue(), emaSpreadPct,
                        bodyPct, upperWickPct, lowerWickPct, upCount, volumeSoFarRatio,
                        currentPrice, actualFinalClose, remainingDriftPct));
            }
            globalIndex += today.size();
        }
        return rows;
    }

    /** Today's current feature state (as of the most recent ingested candle) — for live inference,
     * not backtesting. Unlike {@link #export}, doesn't require the day to be over. */
    public Optional<LiveFeatureSnapshot> liveFeatures(String instrumentTag) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<BigDecimal> closes = hourly.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> rsi14 = RsiCalculator.calculate(closes, RSI_PERIOD);
        List<BigDecimal> ema9 = EmaCalculator.calculate(closes, EMA_SHORT_PERIOD);
        List<BigDecimal> ema21 = EmaCalculator.calculate(closes, EMA_LONG_PERIOD);

        List<List<OhlcvCandle>> days = DailyBarAggregator.groupByDay(hourly);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourly);

        if (days.isEmpty()) {
            return Optional.empty();
        }
        int i = days.size() - 1;
        List<OhlcvCandle> today = days.get(i);
        int h = today.size() - 1;
        int idx = hourly.size() - 1;

        BigDecimal rsiVal = rsi14.get(idx);
        BigDecimal ema9Val = ema9.get(idx);
        BigDecimal ema21Val = ema21.get(idx);
        if (rsiVal == null || ema9Val == null || ema21Val == null) {
            return Optional.empty();
        }

        double dayOpen = today.get(0).getOpen().doubleValue();
        OhlcvCandle candle = today.get(h);
        double currentPrice = candle.getClose().doubleValue();
        double runningHigh = Double.NEGATIVE_INFINITY;
        double runningLow = Double.POSITIVE_INFINITY;
        long volumeSoFar = 0;
        boolean volumeKnown = true;
        for (OhlcvCandle c : today) {
            runningHigh = Math.max(runningHigh, c.getHigh().doubleValue());
            runningLow = Math.min(runningLow, c.getLow().doubleValue());
            if (c.getVolume() != null) {
                volumeSoFar += c.getVolume();
            } else {
                volumeKnown = false;
            }
        }
        Double trailingAvgDailyVolume = trailingAvgVolume(dailyBars, i);
        Double volumeSoFarRatio = (volumeKnown && trailingAvgDailyVolume != null && trailingAvgDailyVolume > 0)
                ? volumeSoFar / trailingAvgDailyVolume
                : null;

        double returnSoFarPct = (currentPrice - dayOpen) / dayOpen * 100;
        double volatilitySoFarPct = (runningHigh - runningLow) / dayOpen * 100;
        double emaSpreadPct = ema9Val.subtract(ema21Val).doubleValue() / currentPrice * 100;

        double open = candle.getOpen().doubleValue();
        double high = candle.getHigh().doubleValue();
        double low = candle.getLow().doubleValue();
        double range = high - low;
        double bodyPct = range > 1e-9 ? (currentPrice - open) / range * 100 : 0.0;
        double upperWickPct = range > 1e-9 ? (high - Math.max(open, currentPrice)) / range * 100 : 0.0;
        double lowerWickPct = range > 1e-9 ? (Math.min(open, currentPrice) - low) / range * 100 : 0.0;

        int windowStart = Math.max(0, h - 2);
        int upCount = 0;
        for (int j = windowStart; j <= h; j++) {
            OhlcvCandle c = today.get(j);
            if (c.getClose().compareTo(c.getOpen()) > 0) {
                upCount++;
            }
        }

        String tradingDate = today.get(0).getTs().atZoneSameInstant(IST).toLocalDate().toString();
        return Optional.of(new LiveFeatureSnapshot(
                instrumentTag, tradingDate, h,
                returnSoFarPct, volatilitySoFarPct, rsiVal.doubleValue(), emaSpreadPct,
                bodyPct, upperWickPct, lowerWickPct, upCount, volumeSoFarRatio, currentPrice));
    }

    /** Average total daily volume over the {@value #VOLUME_LOOKBACK_DAYS} trading days strictly
     * before day index {@code i} — null if fewer than that many prior days exist, or any of them
     * has unknown volume (e.g. the NIFTY/BANKNIFTY index, which carries no real volume). */
    private Double trailingAvgVolume(List<OhlcvCandle> dailyBars, int i) {
        if (i < VOLUME_LOOKBACK_DAYS) {
            return null;
        }
        long sum = 0;
        for (int j = i - VOLUME_LOOKBACK_DAYS; j < i; j++) {
            Long volume = dailyBars.get(j).getVolume();
            if (volume == null) {
                return null;
            }
            sum += volume;
        }
        return sum / (double) VOLUME_LOOKBACK_DAYS;
    }

    public List<IntradayFeatureRow> exportAll() {
        List<String> tags = Stream.concat(
                Stream.of(Instrument.NIFTY.name(), Instrument.BANKNIFTY.name()),
                Nifty50Constituents.SYMBOLS.stream()
        ).toList();

        List<IntradayFeatureRow> all = new ArrayList<>();
        for (String tag : tags) {
            all.addAll(export(tag));
        }
        return all;
    }
}
