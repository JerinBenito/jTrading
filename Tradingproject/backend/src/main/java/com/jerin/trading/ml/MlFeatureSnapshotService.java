package com.jerin.trading.ml;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.forecast.DailyBarAggregator;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.ingestion.Nifty50Constituents;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Builds and maintains a growing, labeled feature dataset — one row per (instrument, trading
 * day) — purely as groundwork for a future ML phase (see project long-term roadmap). Nothing
 * here is read by any live prediction; {@link com.jerin.trading.forecast.DailyForecastPredictionService}
 * and friends remain exactly as validated. Deliberately recomputes and upserts every day's row
 * fresh each call (same "recompute from stored history" convention as every backtest service in
 * this codebase) rather than incrementally patching — simplest way to keep forward-return labels
 * correct as more days pass, and cheap enough at this data size (a few hundred days per instrument).
 */
@Service
public class MlFeatureSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(MlFeatureSnapshotService.class);
    private static final String SOURCE_INTERVAL = "1h";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int[] LOOKBACK_HORIZONS = {5, 10, 20, 40};
    private static final int EMA_SHORT = 9;
    private static final int EMA_LONG = 21;
    private static final int RSI_PERIOD = 14;
    private static final int ATR_PERIOD = 14;

    private final OhlcvCandleRepository candleRepository;
    private final MlFeatureSnapshotRepository snapshotRepository;

    public MlFeatureSnapshotService(OhlcvCandleRepository candleRepository, MlFeatureSnapshotRepository snapshotRepository) {
        this.candleRepository = candleRepository;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional
    public MlFeatureSnapshotResult computeAndSave(String instrumentTag) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);
        if (daily.size() < LOOKBACK_HORIZONS[LOOKBACK_HORIZONS.length - 1] + 1) {
            return new MlFeatureSnapshotResult(instrumentTag, 0);
        }

        List<BigDecimal> closes = daily.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> ema9Series = EmaCalculator.calculate(closes, EMA_SHORT);
        List<BigDecimal> ema21Series = EmaCalculator.calculate(closes, EMA_LONG);
        List<BigDecimal> rsiSeries = RsiCalculator.calculate(closes, RSI_PERIOD);
        List<BigDecimal> atrSeries = AtrCalculator.calculate(daily, ATR_PERIOD);

        int minLookback = LOOKBACK_HORIZONS[LOOKBACK_HORIZONS.length - 1];
        int saved = 0;
        for (int i = minLookback; i < daily.size(); i++) {
            MlFeatureSnapshot snapshot = buildSnapshot(instrumentTag, daily, closes, ema9Series, ema21Series, rsiSeries, atrSeries, i);
            snapshotRepository.save(snapshot);
            saved++;
        }
        log.info("Computed {} ML feature snapshot(s) for {}", saved, instrumentTag);
        return new MlFeatureSnapshotResult(instrumentTag, saved);
    }

    private MlFeatureSnapshot buildSnapshot(String instrumentTag, List<OhlcvCandle> daily, List<BigDecimal> closes,
                                             List<BigDecimal> ema9Series, List<BigDecimal> ema21Series,
                                             List<BigDecimal> rsiSeries, List<BigDecimal> atrSeries, int i) {
        OhlcvCandle today = daily.get(i);
        BigDecimal prevClose = closes.get(i - 1);
        BigDecimal close = closes.get(i);

        MlFeatureSnapshot.MlFeatureSnapshotBuilder builder = MlFeatureSnapshot.builder()
                .instrument(instrumentTag)
                .tradingDate(today.getTs().atZoneSameInstant(IST).toLocalDate())
                .open(today.getOpen())
                .high(today.getHigh())
                .low(today.getLow())
                .close(close)
                .volume(today.getVolume())
                .dailyReturnPct(pctChange(prevClose, close))
                .gapFromPrevClosePct(pctChange(prevClose, today.getOpen()))
                .intradayRangePct(today.getHigh().subtract(today.getLow())
                        .divide(today.getOpen(), 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP))
                .ema9(ema9Series.get(i))
                .ema21(ema21Series.get(i))
                .emaSpreadPct(emaSpreadPct(ema9Series.get(i), ema21Series.get(i), close))
                .rsi14(rsiSeries.get(i))
                .atr14(atrSeries.get(i))
                .computedAt(OffsetDateTime.now());

        for (int horizon : LOOKBACK_HORIZONS) {
            BigDecimal lookbackReturn = i - horizon >= 0 ? pctChange(closes.get(i - horizon), close) : null;
            BigDecimal forwardReturn = i + horizon < closes.size() ? pctChange(close, closes.get(i + horizon)) : null;
            switch (horizon) {
                case 5 -> { builder.return5dPct(lookbackReturn); builder.forwardReturn5dPct(forwardReturn); }
                case 10 -> { builder.return10dPct(lookbackReturn); builder.forwardReturn10dPct(forwardReturn); }
                case 20 -> { builder.return20dPct(lookbackReturn); builder.forwardReturn20dPct(forwardReturn); }
                case 40 -> { builder.return40dPct(lookbackReturn); builder.forwardReturn40dPct(forwardReturn); }
                default -> throw new IllegalStateException("Unhandled horizon: " + horizon);
            }
        }

        MlFeatureSnapshot snapshot = builder.build();
        Optional<MlFeatureSnapshot> existing = snapshotRepository.findByInstrumentAndTradingDate(instrumentTag, snapshot.getTradingDate());
        existing.ifPresent(e -> snapshot.setId(e.getId()));
        return snapshot;
    }

    private BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (from.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return to.subtract(from).divide(from, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal emaSpreadPct(BigDecimal ema9, BigDecimal ema21, BigDecimal close) {
        if (ema9 == null || ema21 == null || close.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return ema9.subtract(ema21).divide(close, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    public List<MlFeatureSnapshotResult> backfillAll() {
        List<String> tags = Stream.concat(
                Stream.of(Instrument.NIFTY.name(), Instrument.BANKNIFTY.name()),
                Nifty50Constituents.SYMBOLS.stream()
        ).toList();

        List<MlFeatureSnapshotResult> results = new ArrayList<>();
        for (String tag : tags) {
            results.add(computeAndSave(tag));
        }
        return results;
    }
}
