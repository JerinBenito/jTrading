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
 * NOT a validated model — same observation-only status {@link AiPredictionService} and
 * {@link com.jerin.trading.ml.AdaptiveSelectionService} launched with. Runs
 * {@link DailyHmmRegimeModel} live, once per trading day, even though its own walk-forward
 * backtest currently loses to random walk on 2 years of real history (NIFTY 0.5483% vs 0.4857%,
 * BANKNIFTY 0.6299% vs 0.5752% — see that model's javadoc). Kept running live anyway, at the
 * user's explicit request: the reasoning is that consistently harvested real-time data could
 * eventually tell a different story than the historical backtest alone — this ledger exists to
 * find out honestly, not to claim it already has.
 *
 * Reuses the {@code hourly_predictions} table under its own interval tag ({@code "1d_hmm"})
 * rather than a new table — same shape (predicted_close/range/actual_close/error_pct) applies
 * unchanged, and it stays cleanly separate from the live
 * {@code DAILY_RANDOM_WALK_GATED_BIAS_CORRECTION} track (different interval value, so no
 * unique-constraint collision). Deliberately no bias correction or range calibration layered on
 * top, unlike the deterministic model — this tracks the HMM's own raw behavior so its real
 * accuracy stays interpretable.
 */
@Service
public class DailyHmmPredictionService {

    private static final Logger log = LoggerFactory.getLogger(DailyHmmPredictionService.class);
    static final String INTERVAL = "1d_hmm";
    private static final String SOURCE_INTERVAL = "1h";
    private static final String MODEL_NAME = "DAILY_HMM_REGIME";
    private static final int ATR_PERIOD = 14;
    private static final int MIN_DAILY_BARS = 42; // model needs >=40 return observations plus the day being predicted
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository predictionRepository;
    private final DailyHmmRegimeModel model;

    public DailyHmmPredictionService(OhlcvCandleRepository candleRepository,
                                      HourlyPredictionRepository predictionRepository,
                                      DailyHmmRegimeModel model) {
        this.candleRepository = candleRepository;
        this.predictionRepository = predictionRepository;
        this.model = model;
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
                continue;
            }
            BigDecimal actual = dayCandles.get(dayCandles.size() - 1).getClose();
            double errorPct = actual.subtract(prediction.getPredictedClose())
                    .divide(actual, 6, RoundingMode.HALF_UP).doubleValue() * 100;

            prediction.setActualClose(actual);
            prediction.setErrorPct(BigDecimal.valueOf(errorPct).setScale(4, RoundingMode.HALF_UP));
            prediction.setEvaluatedAt(OffsetDateTime.now());
            predictionRepository.save(prediction);
            evaluated++;
        }
        if (evaluated > 0) {
            log.info("Evaluated {} pending HMM prediction(s) for {}", evaluated, instrumentTag);
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
        if (dailyBars.size() < MIN_DAILY_BARS) {
            return null;
        }

        ForecastContext context = new ForecastContext(dailyBars, null, null, AtrCalculator.calculate(dailyBars, ATR_PERIOD));
        ForecastPrediction prediction = model.predictClose(dailyBars.size() - 1, context);
        if (prediction == null) {
            return null;
        }

        HourlyPrediction row = HourlyPrediction.builder()
                .instrument(instrumentTag)
                .interval(INTERVAL)
                .modelName(MODEL_NAME)
                .predictedAtTs(predictedForTs)
                .predictedForTs(predictedForTs)
                .predictedClose(prediction.predictedClose().setScale(2, RoundingMode.HALF_UP))
                .rangeLow(prediction.rangeLow())
                .rangeHigh(prediction.rangeHigh())
                .build();

        return predictionRepository.save(row);
    }

    private List<OhlcvCandle> candlesForDay(String instrumentTag, LocalDate date) {
        OffsetDateTime dayStart = date.atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = date.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        return candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                instrumentTag, SOURCE_INTERVAL, dayStart, dayEnd);
    }
}
