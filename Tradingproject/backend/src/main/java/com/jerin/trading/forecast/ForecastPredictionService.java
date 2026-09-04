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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The live rolling forecast loop: each cycle, evaluate any prediction whose target hour has
 * now happened, then record a new prediction for the next hour. Model is the random-walk
 * baseline (Phase 1 backtest showed it beats the momentum model on both instruments) plus a
 * significance-gated bias correction ({@link BiasCorrectionCalculator}) — replaces a naive
 * flat 20-sample average that was found (2026-08-25, real production data) to make predictions
 * worse, not better. The gated version was validated via {@link BiasCorrectionBacktestService}
 * against 2 years of real history: it neutralizes the naive approach's harm and lands
 * essentially on par with plain random walk (NIFTY 0.1858% vs 0.1852% error; BANKNIFTY 0.2168%
 * vs 0.2159%, with slightly better range coverage) — correctly recognizing that at hourly
 * granularity there's rarely a real, non-noise bias worth correcting for.
 *
 * The range width also self-calibrates now ({@link RangeCalibrationService} /
 * {@link RangeCalibrator}, live since 2026-08-26): the fixed ±1x ATR range was found to
 * under-cover in the walk-forward backtest (NIFTY 85.62%, BANKNIFTY 86.62% actual-within-range
 * against a 90% target), so the multiplier now adapts from real evaluated outcomes instead of
 * assuming raw ATR is automatically the right width.
 */
@Service
public class ForecastPredictionService {

    private static final Logger log = LoggerFactory.getLogger(ForecastPredictionService.class);
    private static final String MODEL_NAME = "RANDOM_WALK_GATED_BIAS_CORRECTION";
    private static final int BIAS_WINDOW = 50;
    private static final int ATR_PERIOD = 14;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository predictionRepository;
    private final RandomWalkForecastModel baseModel;
    private final RangeCalibrationService rangeCalibrationService;

    public ForecastPredictionService(OhlcvCandleRepository candleRepository,
                                      HourlyPredictionRepository predictionRepository,
                                      RandomWalkForecastModel baseModel,
                                      RangeCalibrationService rangeCalibrationService) {
        this.candleRepository = candleRepository;
        this.predictionRepository = predictionRepository;
        this.baseModel = baseModel;
        this.rangeCalibrationService = rangeCalibrationService;
    }

    @Transactional
    public int evaluatePending(String instrument, String interval) {
        List<HourlyPrediction> pending = predictionRepository
                .findByInstrumentAndIntervalAndActualCloseIsNull(instrument, interval);

        int evaluated = 0;
        for (HourlyPrediction prediction : pending) {
            Optional<OhlcvCandle> candle = candleRepository.findByInstrumentAndIntervalAndTs(
                    instrument, interval, prediction.getPredictedForTs());
            if (candle.isEmpty()) {
                continue;
            }
            BigDecimal actual = candle.get().getClose();
            double errorPct = actual.subtract(prediction.getPredictedClose())
                    .divide(actual, 6, RoundingMode.HALF_UP).doubleValue() * 100;

            boolean covered = actual.compareTo(prediction.getRangeLow()) >= 0 && actual.compareTo(prediction.getRangeHigh()) <= 0;
            rangeCalibrationService.recordOutcome(instrument, interval, covered);

            prediction.setActualClose(actual);
            prediction.setErrorPct(BigDecimal.valueOf(errorPct).setScale(4, RoundingMode.HALF_UP));
            prediction.setEvaluatedAt(OffsetDateTime.now());
            predictionRepository.save(prediction);
            evaluated++;
        }
        if (evaluated > 0) {
            log.info("Evaluated {} pending prediction(s) for {}", evaluated, instrument);
        }
        return evaluated;
    }

    @Transactional
    public HourlyPrediction recordNextPrediction(String instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository
                .findTop200ByInstrumentAndIntervalOrderByTsDesc(instrument, interval);
        Collections.reverse(candles);
        if (candles.isEmpty()) {
            return null;
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        ForecastContext context = new ForecastContext(candles, null, null,
                AtrCalculator.calculate(candles, ATR_PERIOD));

        int lastIndex = candles.size() - 1;
        ForecastPrediction basePrediction = baseModel.predictNext(lastIndex, context);
        if (basePrediction == null) {
            return null;
        }

        OffsetDateTime latestTs = candles.get(lastIndex).getTs();
        OffsetDateTime predictedForTs = latestTs.plus(Duration.ofHours(1));

        if (!isLikelyTradingHour(predictedForTs)) {
            // e.g. the last candle of the day (~15:15 IST) + 1h lands after close — there will
            // never be a candle to evaluate against, so don't create a prediction doomed to hang forever.
            log.debug("Skipping prediction for {} — {} falls outside market hours", instrument, predictedForTs);
            return null;
        }

        if (predictionRepository.findByInstrumentAndIntervalAndPredictedForTs(instrument, interval, predictedForTs).isPresent()) {
            return null; // already predicted this hour, don't duplicate
        }

        BigDecimal bias = rollingBias(instrument, interval);
        BigDecimal correctedClose = basePrediction.predictedClose().add(bias).setScale(2, RoundingMode.HALF_UP);
        double multiplier = rangeCalibrationService.currentMultiplier(instrument, interval);
        BigDecimal rangeWidth = basePrediction.rangeHigh().subtract(basePrediction.predictedClose())
                .multiply(BigDecimal.valueOf(multiplier)).setScale(4, RoundingMode.HALF_UP);

        HourlyPrediction prediction = HourlyPrediction.builder()
                .instrument(instrument)
                .interval(interval)
                .modelName(MODEL_NAME)
                .predictedAtTs(latestTs)
                .predictedForTs(predictedForTs)
                .predictedClose(correctedClose)
                .rangeLow(correctedClose.subtract(rangeWidth))
                .rangeHigh(correctedClose.add(rangeWidth))
                .biasCorrectionApplied(bias)
                .build();

        return predictionRepository.save(prediction);
    }

    /**
     * Rough NSE-hours check (9am-4pm IST, weekdays) — not a full trading calendar (doesn't
     * know about holidays), same class of approximation as the existing cron schedule.
     * Good enough to avoid predicting into the overnight/weekend gap after market close.
     */
    boolean isLikelyTradingHour(OffsetDateTime ts) {
        ZonedDateTime ist = ts.atZoneSameInstant(IST);
        DayOfWeek day = ist.getDayOfWeek();
        int hour = ist.getHour();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && hour >= 9 && hour < 16;
    }

    /**
     * Significance-gated bias correction over the last BIAS_WINDOW evaluated predictions —
     * see {@link BiasCorrectionCalculator} for the statistical logic. Zero until enough
     * evaluated history exists, and zero whenever the observed bias isn't distinguishable
     * from noise.
     */
    private BigDecimal rollingBias(String instrument, String interval) {
        List<HourlyPrediction> recent = predictionRepository.findRecentEvaluated(instrument, interval);
        List<Double> errors = recent.stream()
                .limit(BIAS_WINDOW)
                .map(p -> p.getActualClose().subtract(p.getPredictedClose()).doubleValue())
                .toList();
        return BiasCorrectionCalculator.calculate(errors);
    }
}
