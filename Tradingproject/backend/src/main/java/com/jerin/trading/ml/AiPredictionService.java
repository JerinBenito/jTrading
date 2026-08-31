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

    private final AiPredictionRepository predictionRepository;
    private final OhlcvCandleRepository candleRepository;

    public AiPredictionService(AiPredictionRepository predictionRepository, OhlcvCandleRepository candleRepository) {
        this.predictionRepository = predictionRepository;
        this.candleRepository = candleRepository;
    }

    @Transactional
    public AiPrediction recordPrediction(String instrument, String horizon, String valueType, String modelVersion,
                                          BigDecimal predictedValue, BigDecimal baselineValue, LocalDate targetDate) {
        Optional<AiPrediction> existing = predictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, horizon, targetDate);
        AiPrediction prediction = existing.orElseGet(AiPrediction::new);
        prediction.setInstrument(instrument);
        prediction.setHorizon(horizon);
        prediction.setValueType(valueType);
        prediction.setModelVersion(modelVersion);
        prediction.setPredictedAtTs(OffsetDateTime.now());
        prediction.setTargetDate(targetDate);
        prediction.setPredictedValue(predictedValue);
        prediction.setBaselineValue(baselineValue);
        return predictionRepository.save(prediction);
    }

    @Transactional
    public int evaluatePending() {
        List<AiPrediction> pending = predictionRepository.findByActualValueIsNull();
        LocalDate today = OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        int evaluated = 0;

        for (AiPrediction prediction : pending) {
            BigDecimal actual = "INTRADAY".equals(prediction.getHorizon())
                    ? actualIntradayClose(prediction, today)
                    : actualForwardReturn(prediction, today);
            if (actual == null) {
                continue; // outcome not knowable yet
            }

            prediction.setActualValue(actual);
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
            evaluated++;
        }
        if (evaluated > 0) {
            log.info("Evaluated {} pending AI prediction(s)", evaluated);
        }
        return evaluated;
    }

    /** INTRADAY: the actual close of the prediction's own target day, once that day is over. */
    private BigDecimal actualIntradayClose(AiPrediction prediction, LocalDate today) {
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
        return dayCandles.get(dayCandles.size() - 1).getClose();
    }

    /** FORWARD_Nd: the actual % return from the target date's close to N trading days later, once that day exists. */
    private BigDecimal actualForwardReturn(AiPrediction prediction, LocalDate today) {
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
        return toClose.subtract(fromClose).divide(fromClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    public List<AiPrediction> history(String instrument, String horizon) {
        return predictionRepository.findByInstrumentAndHorizonOrderByTargetDateDesc(instrument, horizon);
    }

    public RollingAccuracy rollingAccuracy(String instrument, String horizon, int window) {
        List<AiPrediction> recent = predictionRepository.findRecentEvaluated(instrument, horizon).stream()
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
}
