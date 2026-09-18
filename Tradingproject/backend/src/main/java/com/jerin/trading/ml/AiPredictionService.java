package com.jerin.trading.ml;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.forecast.DailyBarAggregator;
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
import java.util.Map;
import java.util.Optional;

/**
 * Records AI (ML) model predictions and evaluates them against real outcomes once known — the
 * live counterpart to the offline walk-forward backtests in {@code ml-training/}. Deliberately
 * mirrors {@link com.jerin.trading.forecast.DailyForecastPredictionService}'s record/evaluate
 * discipline, applied to the AI model instead of the deterministic one.
 *
 * The model itself is not proven (offline validation as of 2026-08-30 found no edge over the
 * baseline at any horizon tested) — this service does not change that. It exists so the live
 * track record is honest and visible, and judged on {@link #rollingAccuracy} across many
 * predictions, never on any single one.
 */
@Service
public class AiPredictionService {

    private static final Logger log = LoggerFactory.getLogger(AiPredictionService.class);
    private static final String SOURCE_INTERVAL = "1h";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Map<String, Integer> FORWARD_HORIZON_DAYS = Map.of(
            "FORWARD_5D", 5, "FORWARD_10D", 10, "FORWARD_20D", 20, "FORWARD_40D", 40);
    /** Same-day-close outcome resolution applies to both the raw AI prediction and
     * {@link AdaptiveSelectionService}'s pick — both target the same day's actual close. */
    private static final java.util.Set<String> INTRADAY_LIKE_HORIZONS = java.util.Set.of("INTRADAY", "INTRADAY_ADAPTIVE");

    /** NSE's close. An INTRADAY call recorded after this on its own target day is anchored to a
     * "current price" that already IS the final close, so its baseline error is exactly zero and
     * its own error is a trivial rounding-sized nudge — found 2026-09-18 to have contaminated 4 of
     * 11 evaluated days (every instrument, every metric) after manual post-close test dispatches
     * overwrote the day's real ~3 PM call. Such calls say nothing about forecasting skill. */
    private static final java.time.LocalTime INTRADAY_CUTOFF = java.time.LocalTime.of(15, 30);

    /** True if {@code at} is after the intraday recording cutoff on {@code targetDate}, i.e. any
     * INTRADAY call made at that moment would already know the day's close. */
    public static boolean isPastIntradayCutoff(OffsetDateTime at, LocalDate targetDate) {
        return at.atZoneSameInstant(IST).isAfter(targetDate.atTime(INTRADAY_CUTOFF).atZone(IST));
    }

    /** Whether a new call for this horizon/target day should be refused right now. Only the raw
     * INTRADAY horizon is guarded — INTRADAY_ADAPTIVE is anchored to the deterministic price, not
     * the live price, so it can't collapse onto the close this way. */
    public boolean isPastRecordingWindow(String horizon, LocalDate targetDate) {
        return "INTRADAY".equals(horizon) && isPastIntradayCutoff(OffsetDateTime.now(), targetDate);
    }

    private final AiPredictionRepository predictionRepository;
    private final AiPredictionSnapshotRepository snapshotRepository;
    private final OhlcvCandleRepository candleRepository;

    public AiPredictionService(AiPredictionRepository predictionRepository,
                                AiPredictionSnapshotRepository snapshotRepository,
                                OhlcvCandleRepository candleRepository) {
        this.predictionRepository = predictionRepository;
        this.snapshotRepository = snapshotRepository;
        this.candleRepository = candleRepository;
    }

    @Transactional
    public AiPrediction recordPrediction(String instrument, String horizon, String valueType, String modelVersion,
                                          BigDecimal predictedValue, BigDecimal baselineValue, LocalDate targetDate,
                                          BigDecimal predictedPrice, BigDecimal baselinePrice) {
        OffsetDateTime now = OffsetDateTime.now();
        BigDecimal resolvedPredictedPrice = "PRICE".equals(valueType) ? predictedValue : predictedPrice;
        BigDecimal resolvedBaselinePrice = "PRICE".equals(valueType) ? baselineValue : baselinePrice;

        // Immutable append — never overwritten, unlike the upsert below. This is what preserves
        // the day's first call once later hourly re-predictions come in. Its deviation from the
        // first call and from the previous call are stored now, while both are at hand; its own
        // error against the real outcome is stamped later, by evaluatePending.
        Optional<AiPredictionSnapshot> firstOfDay = snapshotRepository.findFirstOfDay(instrument, horizon, targetDate);
        Optional<AiPredictionSnapshot> previousCall = snapshotRepository.findLastOfDay(instrument, horizon, targetDate);
        int sequence = (int) snapshotRepository.countByInstrumentAndHorizonAndTargetDate(instrument, horizon, targetDate) + 1;
        boolean isPrice = "PRICE".equals(valueType);
        BigDecimal nudge = predictedValue.subtract(baselineValue);
        BigDecimal deviationFromFirst = firstOfDay.map(f -> predictedValue.subtract(f.getPredictedValue())).orElse(BigDecimal.ZERO);
        BigDecimal deviationFromPrevious = previousCall.map(p -> predictedValue.subtract(p.getPredictedValue())).orElse(null);
        snapshotRepository.save(AiPredictionSnapshot.builder()
                .instrument(instrument).horizon(horizon).valueType(valueType).modelVersion(modelVersion)
                .predictedAtTs(now).targetDate(targetDate)
                .predictedValue(predictedValue).baselineValue(baselineValue)
                .predictedPrice(resolvedPredictedPrice).baselinePrice(resolvedBaselinePrice)
                .sequenceInDay(sequence)
                .afterClose("INTRADAY".equals(horizon) && isPastIntradayCutoff(now, targetDate))
                .deviationFromFirst(deviationFromFirst)
                .deviationFromPrevious(deviationFromPrevious)
                .nudge(nudge)
                .nudgePct(isPrice ? pctOf(nudge, baselineValue) : null)
                .deviationFromFirstPct(isPrice ? pctOf(deviationFromFirst, firstOfDay.map(AiPredictionSnapshot::getPredictedValue).orElse(predictedValue)) : null)
                .deviationFromPreviousPct(isPrice && previousCall.isPresent()
                        ? pctOf(deviationFromPrevious, previousCall.get().getPredictedValue()) : null)
                .build());

        Optional<AiPrediction> existing = predictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, horizon, targetDate);
        AiPrediction prediction = existing.orElseGet(AiPrediction::new);
        prediction.setInstrument(instrument);
        prediction.setHorizon(horizon);
        prediction.setValueType(valueType);
        prediction.setModelVersion(modelVersion);
        prediction.setPredictedAtTs(now);
        prediction.setTargetDate(targetDate);
        prediction.setPredictedValue(predictedValue);
        prediction.setBaselineValue(baselineValue);
        // For PRICE-typed predictions (INTRADAY), the value already IS a price — always derive
        // it from predictedValue/baselineValue rather than trusting a possibly-stale caller-
        // supplied one. For RETURN_PCT (FORWARD_*), the caller supplies the converted price
        // since only it knows the anchor close the % was computed from.
        prediction.setPredictedPrice(resolvedPredictedPrice);
        prediction.setBaselinePrice(resolvedBaselinePrice);
        return predictionRepository.save(prediction);
    }

    @Transactional
    public int evaluatePending() {
        List<AiPrediction> pending = predictionRepository.findByActualValueIsNull();
        LocalDate today = OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        int evaluated = 0;

        for (AiPrediction prediction : pending) {
            ActualOutcome outcome = INTRADAY_LIKE_HORIZONS.contains(prediction.getHorizon())
                    ? actualIntradayClose(prediction, today)
                    : actualForwardReturn(prediction, today);
            if (outcome == null) {
                continue; // outcome not knowable yet
            }

            BigDecimal actual = outcome.value();
            prediction.setActualValue(actual);
            prediction.setActualPrice(outcome.price());
            BigDecimal aiError = actual.subtract(prediction.getPredictedValue()).abs().setScale(4, RoundingMode.HALF_UP);
            BigDecimal baselineError = actual.subtract(prediction.getBaselineValue()).abs().setScale(4, RoundingMode.HALF_UP);
            prediction.setAiErrorAbs(aiError);
            prediction.setBaselineErrorAbs(baselineError);
            prediction.setBetterThanBaseline(aiError.compareTo(baselineError) < 0);

            BigDecimal actualDirection = actual.subtract(prediction.getBaselineValue());
            BigDecimal predictedDirection = prediction.getPredictedValue().subtract(prediction.getBaselineValue());
            prediction.setDirectionCorrect(actualDirection.signum() == predictedDirection.signum());

            prediction.setEvaluatedAt(OffsetDateTime.now());
            predictionRepository.save(prediction);
            stampSnapshotErrors(prediction, actual);
            evaluated++;
        }
        if (evaluated > 0) {
            log.info("Evaluated {} pending AI prediction(s)", evaluated);
        }
        return evaluated;
    }

    /** {@code part} as a percentage of {@code whole}, 4 decimal places; null when the whole is zero. */
    private static BigDecimal pctOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return null;
        }
        return part.divide(whole, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    /** Gives EVERY call recorded for this instrument/horizon/day its own error against the real
     * outcome — not only the latest one that {@link AiPrediction} keeps — so the first call's error,
     * each later call's error, and how the error changed across the day can all be read back. */
    private void stampSnapshotErrors(AiPrediction evaluated, BigDecimal actual) {
        for (AiPredictionSnapshot s : snapshotRepository.findByInstrumentAndHorizonAndTargetDateOrderByPredictedAtTsAsc(
                evaluated.getInstrument(), evaluated.getHorizon(), evaluated.getTargetDate())) {
            BigDecimal error = s.getPredictedValue().subtract(actual);
            BigDecimal baselineError = s.getBaselineValue().subtract(actual).abs();
            s.setActualValue(actual);
            s.setErrorSigned(error.setScale(4, RoundingMode.HALF_UP));
            s.setErrorAbs(error.abs().setScale(4, RoundingMode.HALF_UP));
            s.setErrorPct("PRICE".equals(s.getValueType()) && actual.signum() != 0
                    ? error.divide(actual, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                    : null);
            s.setBaselineErrorAbs(baselineError.setScale(4, RoundingMode.HALF_UP));
            s.setBetterThanBaseline(error.abs().compareTo(baselineError) < 0);
            s.setDirectionCorrect(actual.subtract(s.getBaselineValue()).signum()
                    == s.getPredictedValue().subtract(s.getBaselineValue()).signum());
            s.setEvaluatedAt(evaluated.getEvaluatedAt());
            snapshotRepository.save(s);
        }
    }

    /** The stored per-call table for the last {@code days} target days: each call's prediction, its
     * deviation from the day's first call and from the previous call, and — once the day is over —
     * its own error. Oldest day first, calls in the order they were made. */
    public List<AiPredictionSnapshot> snapshotHistory(String instrument, String horizon, int days) {
        List<AiPredictionSnapshot> all = snapshotRepository
                .findByInstrumentAndHorizonOrderByTargetDateAscPredictedAtTsAsc(instrument, horizon);
        java.util.TreeSet<LocalDate> keep = new java.util.TreeSet<>();
        for (AiPredictionSnapshot s : all) {
            keep.add(s.getTargetDate());
        }
        java.util.Set<LocalDate> recent = new java.util.HashSet<>(keep.descendingSet().stream().limit(days).toList());
        return all.stream().filter(s -> recent.contains(s.getTargetDate())).toList();
    }

    /** The realized outcome, both as the value being scored (price for INTRADAY, % return for
     * FORWARD_*) and as a plain rupee price either way. */
    private record ActualOutcome(BigDecimal value, BigDecimal price) {
    }

    /** INTRADAY: the actual close of the prediction's own target day, once that day is over. */
    private ActualOutcome actualIntradayClose(AiPrediction prediction, LocalDate today) {
        if (!today.isAfter(prediction.getTargetDate())) {
            return null;
        }
        OffsetDateTime dayStart = prediction.getTargetDate().atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = prediction.getTargetDate().plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        List<OhlcvCandle> dayCandles = candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                prediction.getInstrument(), SOURCE_INTERVAL, dayStart, dayEnd);
        if (dayCandles.isEmpty()) {
            return null;
        }
        BigDecimal close = dayCandles.get(dayCandles.size() - 1).getClose();
        return new ActualOutcome(close, close);
    }

    /** FORWARD_Nd: the actual % return from the target date's close to N trading days later, once that day exists. */
    private ActualOutcome actualForwardReturn(AiPrediction prediction, LocalDate today) {
        Integer horizonDays = FORWARD_HORIZON_DAYS.get(prediction.getHorizon());
        if (horizonDays == null) {
            return null;
        }
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(prediction.getInstrument(), SOURCE_INTERVAL);
        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);

        int targetIndex = -1;
        for (int i = 0; i < daily.size(); i++) {
            if (daily.get(i).getTs().atZoneSameInstant(IST).toLocalDate().equals(prediction.getTargetDate())) {
                targetIndex = i;
                break;
            }
        }
        int futureIndex = targetIndex + horizonDays;
        if (targetIndex < 0 || futureIndex >= daily.size()) {
            return null; // that many trading days haven't happened yet
        }

        BigDecimal fromClose = daily.get(targetIndex).getClose();
        BigDecimal toClose = daily.get(futureIndex).getClose();
        if (fromClose.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        BigDecimal returnPct = toClose.subtract(fromClose).divide(fromClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
        return new ActualOutcome(returnPct, toClose);
    }

    public List<AiPrediction> history(String instrument, String horizon) {
        return predictionRepository.findByInstrumentAndHorizonOrderByTargetDateDesc(instrument, horizon);
    }

    /** Excludes rows whose baseline equals the actual outcome exactly — for INTRADAY that means the
     * call was made after the close was already known (see {@link #INTRADAY_CUTOFF}), so it can't
     * show skill either way and would only drag both error columns toward a meaningless result. */
    public RollingAccuracy rollingAccuracy(String instrument, String horizon, int window) {
        List<AiPrediction> recent = predictionRepository.findRecentEvaluated(instrument, horizon).stream()
                .filter(p -> p.getBaselineValue().compareTo(p.getActualValue()) != 0)
                .limit(window)
                .toList();
        if (recent.isEmpty()) {
            return new RollingAccuracy(instrument, horizon, window, 0, null, null, null, null);
        }

        double avgAiError = recent.stream().mapToDouble(p -> p.getAiErrorAbs().doubleValue()).average().orElse(0);
        double avgBaselineError = recent.stream().mapToDouble(p -> p.getBaselineErrorAbs().doubleValue()).average().orElse(0);
        double betterPct = recent.stream().filter(p -> Boolean.TRUE.equals(p.getBetterThanBaseline())).count() * 100.0 / recent.size();
        double directionPct = recent.stream().filter(p -> Boolean.TRUE.equals(p.getDirectionCorrect())).count() * 100.0 / recent.size();

        return new RollingAccuracy(
                instrument, horizon, window, recent.size(),
                BigDecimal.valueOf(avgAiError).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(avgBaselineError).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(betterPct).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(directionPct).setScale(2, RoundingMode.HALF_UP));
    }

    /** The fair version of {@link #rollingAccuracy} — see {@link FirstCallRollingAccuracy}. Only
     * days that had a recorded snapshot contribute; days predating the snapshot table's
     * introduction (2026-09-17) are silently skipped since no first-call was ever captured for
     * them. */
    public FirstCallRollingAccuracy firstCallRollingAccuracy(String instrument, String horizon, int window) {
        List<AiPrediction> recent = predictionRepository.findRecentEvaluated(instrument, horizon).stream()
                .limit(window)
                .toList();

        List<BigDecimal> aiErrors = new java.util.ArrayList<>();
        List<BigDecimal> baselineErrors = new java.util.ArrayList<>();
        List<Boolean> betterThanBaseline = new java.util.ArrayList<>();
        List<Boolean> directionCorrect = new java.util.ArrayList<>();
        List<BigDecimal> revisionGradients = new java.util.ArrayList<>();

        for (AiPrediction evaluated : recent) {
            Optional<AiPredictionSnapshot> first = snapshotRepository.findFirstOfDay(instrument, horizon, evaluated.getTargetDate());
            if (first.isEmpty()) {
                continue; // predates the snapshot table, or never recorded — skip rather than guess
            }
            AiPredictionSnapshot firstSnapshot = first.get();
            if (isPastIntradayCutoff(firstSnapshot.getPredictedAtTs(), evaluated.getTargetDate())
                    && "INTRADAY".equals(horizon)) {
                continue; // the "first" call was already post-close — no genuine first call exists for this day
            }
            BigDecimal actual = evaluated.getActualValue();

            BigDecimal aiError = actual.subtract(firstSnapshot.getPredictedValue()).abs();
            BigDecimal baselineError = actual.subtract(firstSnapshot.getBaselineValue()).abs();
            aiErrors.add(aiError);
            baselineErrors.add(baselineError);
            betterThanBaseline.add(aiError.compareTo(baselineError) < 0);

            BigDecimal actualDirection = actual.subtract(firstSnapshot.getBaselineValue());
            BigDecimal predictedDirection = firstSnapshot.getPredictedValue().subtract(firstSnapshot.getBaselineValue());
            directionCorrect.add(actualDirection.signum() == predictedDirection.signum());

            snapshotRepository.findLastOfDay(instrument, horizon, evaluated.getTargetDate())
                    .filter(last -> !"INTRADAY".equals(horizon) || !isPastIntradayCutoff(last.getPredictedAtTs(), evaluated.getTargetDate()))
                    .ifPresent(last -> revisionGradients.add(last.getPredictedValue().subtract(firstSnapshot.getPredictedValue()).abs()));
        }

        if (aiErrors.isEmpty()) {
            return new FirstCallRollingAccuracy(instrument, horizon, window, 0, null, null, null, null, null);
        }

        double avgAiError = aiErrors.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double avgBaselineError = baselineErrors.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double betterPct = betterThanBaseline.stream().filter(Boolean::booleanValue).count() * 100.0 / betterThanBaseline.size();
        double directionPct = directionCorrect.stream().filter(Boolean::booleanValue).count() * 100.0 / directionCorrect.size();
        BigDecimal avgGradient = revisionGradients.isEmpty() ? null
                : BigDecimal.valueOf(revisionGradients.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0))
                        .setScale(4, RoundingMode.HALF_UP);

        return new FirstCallRollingAccuracy(
                instrument, horizon, window, aiErrors.size(),
                BigDecimal.valueOf(avgAiError).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(avgBaselineError).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(betterPct).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(directionPct).setScale(2, RoundingMode.HALF_UP),
                avgGradient);
    }
}
