package com.jerin.trading.monitor;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.forecast.DailyBarAggregator;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.ingestion.Nifty50Constituents;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Phase D, first cut: a live-refreshing view across NIFTY, BANKNIFTY, and the NIFTY 50 basket
 * (kept live by {@link com.jerin.trading.ingestion.EquityBasketIngestionService#ingestTodayForBasket})
 * for the mobile app's monitoring screen. Deliberately does NOT touch
 * {@link com.jerin.trading.pattern.PatternStatsService}'s win-rate
 * stats or {@link com.jerin.trading.signal.SignalService} — those are keyed only by pattern id,
 * not by instrument, so recomputing them against 49 more stocks would silently corrupt the
 * already-trusted NIFTY/BANKNIFTY confidence tiers (a real schema gap, flagged separately, not
 * fixed here). This service only ever reads and computes fresh, never persists.
 */
@Service
public class BasketMonitorService {

    private static final String INTERVAL = "1h";
    private static final String DAILY_INTERVAL = "1d";
    private static final BigDecimal RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    private static final BigDecimal RSI_OVERSOLD = BigDecimal.valueOf(30);

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository predictionRepository;

    public BasketMonitorService(OhlcvCandleRepository candleRepository, HourlyPredictionRepository predictionRepository) {
        this.candleRepository = candleRepository;
        this.predictionRepository = predictionRepository;
    }

    public List<BasketSnapshot> snapshot() {
        List<String> tags = Stream.concat(
                Stream.of(Instrument.NIFTY.name(), Instrument.BANKNIFTY.name()),
                Nifty50Constituents.SYMBOLS.stream()
        ).toList();

        List<BasketSnapshot> results = new ArrayList<>();
        for (String tag : tags) {
            BasketSnapshot snapshot = snapshotFor(tag);
            if (snapshot != null) {
                results.add(snapshot);
            }
        }
        return results;
    }

    private BasketSnapshot snapshotFor(String tag) {
        List<OhlcvCandle> candles = candleRepository.findTop200ByInstrumentAndIntervalOrderByTsDesc(tag, INTERVAL);
        if (candles.size() < 2) {
            return null;
        }
        Collections.reverse(candles);

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        BigDecimal ema9 = lastNonNull(EmaCalculator.calculate(closes, 9));
        BigDecimal ema21 = lastNonNull(EmaCalculator.calculate(closes, 21));
        BigDecimal rsi14 = lastNonNull(RsiCalculator.calculate(closes, 14));

        int last = candles.size() - 1;
        BigDecimal lastClose = closes.get(last);
        BigDecimal prevClose = closes.get(last - 1);
        BigDecimal changePct = prevClose.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : lastClose.subtract(prevClose)
                        .divide(prevClose, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        String trend = trendOf(ema9, ema21);
        String rsiZone = rsiZoneOf(rsi14);

        Optional<HourlyPrediction> todaysPrediction = todaysPrediction(tag, candles);
        BigDecimal predictedClose = todaysPrediction.map(HourlyPrediction::getPredictedClose).orElse(null);
        BigDecimal predictedRangeLow = todaysPrediction.map(HourlyPrediction::getRangeLow).orElse(null);
        BigDecimal predictedRangeHigh = todaysPrediction.map(HourlyPrediction::getRangeHigh).orElse(null);
        BigDecimal deviationFromPredictionPct = predictedClose == null || predictedClose.compareTo(BigDecimal.ZERO) == 0
                ? null
                : lastClose.subtract(predictedClose)
                        .divide(predictedClose, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP);

        return new BasketSnapshot(tag, candles.get(last).getTs(), lastClose, changePct,
                ema9, ema21, trend, rsi14, rsiZone, candles.size(),
                predictedClose, predictedRangeLow, predictedRangeHigh, deviationFromPredictionPct);
    }

    /** Looks up today's same-day close prediction (interval "1d"), keyed by the day's first hourly candle — same convention as {@link com.jerin.trading.forecast.DailyForecastPredictionService}. */
    private Optional<HourlyPrediction> todaysPrediction(String tag, List<OhlcvCandle> ascendingCandles) {
        List<List<OhlcvCandle>> byDay = DailyBarAggregator.groupByDay(ascendingCandles);
        if (byDay.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime predictedForTs = byDay.get(byDay.size() - 1).get(0).getTs();
        return predictionRepository.findByInstrumentAndIntervalAndPredictedForTs(tag, DAILY_INTERVAL, predictedForTs);
    }

    private String trendOf(BigDecimal ema9, BigDecimal ema21) {
        if (ema9 == null || ema21 == null) {
            return "UNKNOWN";
        }
        int cmp = ema9.compareTo(ema21);
        return cmp > 0 ? "BULLISH" : cmp < 0 ? "BEARISH" : "NEUTRAL";
    }

    private String rsiZoneOf(BigDecimal rsi14) {
        if (rsi14 == null) {
            return "UNKNOWN";
        }
        if (rsi14.compareTo(RSI_OVERBOUGHT) >= 0) {
            return "OVERBOUGHT";
        }
        if (rsi14.compareTo(RSI_OVERSOLD) <= 0) {
            return "OVERSOLD";
        }
        return "NEUTRAL";
    }

    private BigDecimal lastNonNull(List<BigDecimal> series) {
        for (int i = series.size() - 1; i >= 0; i--) {
            if (series.get(i) != null) {
                return series.get(i);
            }
        }
        return null;
    }
}
