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
 * NOT a validated model — same observation-only status as {@link DailyHmmPredictionService}.
 * Runs {@link DailyGarchVolatilityModel} live, once per trading day, despite a mixed backtest
 * result on 2 years of real history: genuinely competitive range calibration on BANKNIFTY
 * (89.89% actual-within-range vs a 90% target, ~4% narrower than the fixed-ATR range), weaker
 * on NIFTY (88.11% vs random walk's 91.49%) — see that model's javadoc. Kept running live
 * anyway, at the user's explicit request, on the same reasoning as the HMM track: consistently
 * harvested real-time data could tell a different story than the historical backtest alone.
 *
 * Reuses the {@code hourly_predictions} table under its own interval tag ({@code "1d_garch"})
 * rather than a new table, staying cleanly separate from every other live track (different
 * interval value, no unique-constraint collision). No bias correction or extra range
 * calibration layered on top — this tracks the model's own raw behavior, predicted close
 * included, so its real accuracy (and the fact that its point prediction is identical to
 * random walk by design) stays interpretable.
 */
@Service
public class DailyGarchPredictionService {

    private static final Logger log = LoggerFactory.getLogger(DailyGarchPredictionService.class);
    static final String INTERVAL = "1d_garch";
    private static final String SOURCE_INTERVAL = "1h";
    private static final String MODEL_NAME = "DAILY_GARCH_VOLATILITY";
    private static final int ATR_PERIOD = 14;
    private static final int MIN_DAILY_BARS = 62; // model needs >=60 return observations plus the day being predicted
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository predictionRepository;
    private final DailyGarchVolatilityModel model;

    public DailyGarchPredictionService(OhlcvCandleRepository candleRepository,
                                        HourlyPredictionRepository predictionRepository,
                                        DailyGarchVolatilityModel model) {
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
            log.info("Evaluated {} pending GARCH prediction(s) for {}", evaluated, instrumentTag);
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
