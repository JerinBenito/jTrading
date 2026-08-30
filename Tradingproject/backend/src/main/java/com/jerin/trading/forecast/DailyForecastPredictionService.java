package com.jerin.trading.forecast;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The live same-day open-to-close forecast loop: once per trading day, predict today's close
 * at market open; once that day is over, evaluate it against what actually happened. Reuses
 * the {@code hourly_predictions} table with {@code interval="1d"} rather than a new table —
 * same shape (predicted_close/range/bias_correction/actual_close/error_pct) applies unchanged.
 *
 * Model is {@link DailyRandomWalkModel} (Phase 1 backtest showed it beats the EMA-momentum
 * alternative on both instruments, mirroring the hourly result) plus the same
 * significance-gated bias correction as the hourly loop ({@link BiasCorrectionCalculator}) —
 * validated via {@link DailyBiasCorrectionBacktestService} against 2 years of real history
 * before going live: naive averaging made daily predictions worse on both instruments
 * (NIFTY 0.5045% vs 0.4953% baseline; BANKNIFTY 0.5978% vs 0.5822%), the gated version stays
 * within a hair of the uncorrected baseline on both (0.4963%, 0.5834%) — same conclusion as
 * the hourly case: real, non-noise bias is rare at this granularity too, so the correction
 * mostly stays out of the way, only acting on the rare occasions there's a real signal.
 *
 * Range width self-calibrates too ({@link RangeCalibrationService}, live since 2026-08-26,
 * independently from the hourly loop's multiplier) — the daily range was already close to
 * well-calibrated in the backtest (NIFTY 90.55%, BANKNIFTY 89.94% against a 90% target), so
 * this mostly keeps it that way rather than making a large correction.
 *
 * Takes a plain instrument tag (not the {@code Instrument} enum) since 2026-08-30 — extended to
 * the NIFTY 50 basket stocks (Phase D), which aren't in that enum. Safe to do because
 * {@code hourly_predictions} is genuinely keyed by (instrument, interval, predicted_for_ts),
 * unlike {@code pattern_stats} (keyed by pattern id alone) — no cross-instrument corruption risk.
 */
@Service
public class DailyForecastPredictionService {

    private static final Logger log = LoggerFactory.getLogger(DailyForecastPredictionService.class);
    private static final String INTERVAL = "1d";
    private static final String SOURCE_INTERVAL = "1h";
    private static final String MODEL_NAME = "DAILY_RANDOM_WALK_GATED_BIAS_CORRECTION";
    private static final int BIAS_WINDOW = 60;
    private static final int ATR_PERIOD = 14;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository predictionRepository;
    private final DailyRandomWalkModel baseModel;
    private final RangeCalibrationService rangeCalibrationService;

    public DailyForecastPredictionService(OhlcvCandleRepository candleRepository,
                                           HourlyPredictionRepository predictionRepository,
                                           DailyRandomWalkModel baseModel,
                                           RangeCalibrationService rangeCalibrationService) {
        this.candleRepository = candleRepository;
        this.predictionRepository = predictionRepository;
        this.baseModel = baseModel;
        this.rangeCalibrationService = rangeCalibrationService;
    }

    @Transactional
    public int evaluatePending(String instrumentTag) {
        List<HourlyPrediction> pending = predictionRepository
                .findByInstrumentAndIntervalAndActualCloseIsNull(instrumentTag, INTERVAL);
        if (pending.isEmpty()) {
            return 0;
        }

        LocalDate today = OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        int evaluated = 0;

        for (HourlyPrediction prediction : pending) {
            LocalDate predictionDate = prediction.getPredictedForTs().atZoneSameInstant(IST).toLocalDate();
            if (!today.isAfter(predictionDate)) {
                continue; // that trading day isn't over yet
            }

            List<OhlcvCandle> dayCandles = candlesForDay(instrumentTag, predictionDate);
            if (dayCandles.isEmpty()) {
                continue; // shouldn't happen — a prediction is only ever recorded once that day's first candle exists
            }
            BigDecimal actual = dayCandles.get(dayCandles.size() - 1).getClose();
            double errorPct = actual.subtract(prediction.getPredictedClose())
                    .divide(actual, 6, RoundingMode.HALF_UP).doubleValue() * 100;

            boolean covered = actual.compareTo(prediction.getRangeLow()) >= 0 && actual.compareTo(prediction.getRangeHigh()) <= 0;
            rangeCalibrationService.recordOutcome(instrumentTag, INTERVAL, covered);

            prediction.setActualClose(actual);
            prediction.setErrorPct(BigDecimal.valueOf(errorPct).setScale(4, RoundingMode.HALF_UP));
            prediction.setEvaluatedAt(OffsetDateTime.now());
            predictionRepository.save(prediction);
            evaluated++;
        }
        if (evaluated > 0) {
            log.info("Evaluated {} pending daily prediction(s) for {}", evaluated, instrumentTag);
        }
        return evaluated;
    }

    @Transactional
    public HourlyPrediction recordTodayPrediction(String instrumentTag) {
        LocalDate today = OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        List<OhlcvCandle> todayCandles = candlesForDay(instrumentTag, today);
        if (todayCandles.isEmpty()) {
            return null; // no candle for today yet
        }

        OffsetDateTime predictedForTs = todayCandles.get(0).getTs();
        if (predictionRepository.findByInstrumentAndIntervalAndPredictedForTs(instrumentTag, INTERVAL, predictedForTs).isPresent()) {
            return null; // already predicted today
        }

        List<OhlcvCandle> hourlyHistory = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, SOURCE_INTERVAL);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourlyHistory);
        if (dailyBars.size() < 2) {
            return null; // need at least one prior complete day for ATR
        }

        ForecastContext context = new ForecastContext(dailyBars, null, null, AtrCalculator.calculate(dailyBars, ATR_PERIOD));
        ForecastPrediction basePrediction = baseModel.predictClose(dailyBars.size() - 1, context);
        if (basePrediction == null) {
            return null;
        }

        BigDecimal bias = rollingBias(instrumentTag);
        BigDecimal correctedClose = basePrediction.predictedClose().add(bias).setScale(2, RoundingMode.HALF_UP);
        double multiplier = rangeCalibrationService.currentMultiplier(instrumentTag, INTERVAL);
        BigDecimal rangeWidth = basePrediction.rangeHigh().subtract(basePrediction.predictedClose())
                .multiply(BigDecimal.valueOf(multiplier)).setScale(4, RoundingMode.HALF_UP);

        HourlyPrediction prediction = HourlyPrediction.builder()
                .instrument(instrumentTag)
                .interval(INTERVAL)
                .modelName(MODEL_NAME)
                .predictedAtTs(predictedForTs)
                .predictedForTs(predictedForTs)
                .predictedClose(correctedClose)
                .rangeLow(correctedClose.subtract(rangeWidth))
                .rangeHigh(correctedClose.add(rangeWidth))
                .biasCorrectionApplied(bias)
                .build();

        return predictionRepository.save(prediction);
    }

    private List<OhlcvCandle> candlesForDay(String instrumentTag, LocalDate date) {
        OffsetDateTime dayStart = date.atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = date.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        return candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                instrumentTag, SOURCE_INTERVAL, dayStart, dayEnd);
    }

    /** Significance-gated bias correction over the last BIAS_WINDOW evaluated daily predictions — see {@link BiasCorrectionCalculator}. */
    private BigDecimal rollingBias(String instrumentTag) {
        List<HourlyPrediction> recent = predictionRepository.findRecentEvaluated(instrumentTag, INTERVAL);
        List<Double> errors = recent.stream()
                .limit(BIAS_WINDOW)
                .map(p -> p.getActualClose().subtract(p.getPredictedClose()).doubleValue())
                .toList();
        return BiasCorrectionCalculator.calculate(errors);
    }
}
