package com.jerin.trading.forecast;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Read-only reporting layer on top of {@link DailyForecastPredictionService} — doesn't predict
 * anything new for the *stored* prediction, just shows how the day's actual price moved toward
 * or away from the morning's predicted close, hour by hour. Purely derived from data already
 * stored (the day's "1d" prediction row plus that day's "1h" candles), nothing new persisted.
 *
 * Also computes a live, continuously re-anchored estimate of the close ({@link IntradayReanchorCalculator}):
 * the static morning prediction is never touched all day, but validated (2026-08-26) to become
 * unreliable once the price meaningfully breaks outside its range mid-session — on those days
 * its coverage of the actual close fell to 25-38% in the walk-forward backtest, vs. 92-100%
 * once re-anchored to the latest known price. This gives the frontend both numbers live.
 */
@Service
public class DailyTrajectoryService {

    private static final String DAILY_INTERVAL = "1d";
    private static final String HOURLY_INTERVAL = "1h";
    private static final int ATR_PERIOD = 14;
    /** NSE's standard equity session length, 09:15-15:30 IST — fixed, not derived from today's (possibly still in-progress) candles. */
    private static final long NOMINAL_SESSION_MINUTES = 375;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository predictionRepository;

    public DailyTrajectoryService(OhlcvCandleRepository candleRepository, HourlyPredictionRepository predictionRepository) {
        this.candleRepository = candleRepository;
        this.predictionRepository = predictionRepository;
    }

    /** @param date defaults to today (IST) when null */
    public DailyTrajectory getTrajectory(String instrumentTag, LocalDate date) {
        LocalDate targetDate = date != null ? date : OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();

        OffsetDateTime dayStart = targetDate.atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDate.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        List<OhlcvCandle> dayCandles = candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                instrumentTag, HOURLY_INTERVAL, dayStart, dayEnd);
        if (dayCandles.isEmpty()) {
            return null; // no trading that day, or no data ingested yet
        }

        Optional<HourlyPrediction> prediction = predictionRepository.findByInstrumentAndIntervalAndPredictedForTs(
                instrumentTag, DAILY_INTERVAL, dayCandles.get(0).getTs());
        if (prediction.isEmpty()) {
            return null; // no daily prediction was recorded for this day
        }
        HourlyPrediction p = prediction.get();

        List<TrajectoryPoint> points = dayCandles.stream()
                .map(candle -> toPoint(candle, p.getPredictedClose(), p.getRangeLow(), p.getRangeHigh()))
                .toList();

        BigDecimal currentEstimatedClose = null;
        BigDecimal currentEstimatedRangeLow = null;
        BigDecimal currentEstimatedRangeHigh = null;

        if (p.getActualClose() == null) {
            BigDecimal atr = yesterdaysDailyAtr(instrumentTag, targetDate);
            if (atr != null) {
                OhlcvCandle latest = dayCandles.get(dayCandles.size() - 1);
                long elapsedMinutes = Duration.between(dayCandles.get(0).getTs(), latest.getTs()).toMinutes();
                double remaining = IntradayReanchorCalculator.remainingFraction(elapsedMinutes, NOMINAL_SESSION_MINUTES);
                double width = IntradayReanchorCalculator.remainingRangeWidth(atr.doubleValue(), remaining);
                BigDecimal widthDecimal = BigDecimal.valueOf(width).setScale(2, RoundingMode.HALF_UP);

                currentEstimatedClose = latest.getClose();
                currentEstimatedRangeLow = currentEstimatedClose.subtract(widthDecimal);
                currentEstimatedRangeHigh = currentEstimatedClose.add(widthDecimal);
            }
        }

        return new DailyTrajectory(
                instrumentTag, targetDate, p.getPredictedClose(), p.getRangeLow(), p.getRangeHigh(),
                p.getActualClose(), points,
                currentEstimatedClose, currentEstimatedRangeLow, currentEstimatedRangeHigh);
    }

    /** Daily ATR14 as of the day before `date` — the same basis the static prediction itself was built on. */
    private BigDecimal yesterdaysDailyAtr(String instrumentTag, LocalDate date) {
        List<OhlcvCandle> hourlyHistory = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, HOURLY_INTERVAL);
        List<OhlcvCandle> dailyBars = DailyBarAggregator.aggregate(hourlyHistory);
        int todayIndex = -1;
        for (int i = 0; i < dailyBars.size(); i++) {
            if (dailyBars.get(i).getTs().atZoneSameInstant(IST).toLocalDate().equals(date)) {
                todayIndex = i;
                break;
            }
        }
        if (todayIndex < 1) {
            return null;
        }
        List<BigDecimal> atr14 = AtrCalculator.calculate(dailyBars, ATR_PERIOD);
        return atr14.get(todayIndex - 1);
    }

    private TrajectoryPoint toPoint(OhlcvCandle candle, BigDecimal predictedClose, BigDecimal rangeLow, BigDecimal rangeHigh) {
        BigDecimal actual = candle.getClose();
        BigDecimal deviation = actual.subtract(predictedClose).setScale(2, RoundingMode.HALF_UP);
        BigDecimal deviationPct = deviation.divide(predictedClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
        boolean withinRange = actual.compareTo(rangeLow) >= 0 && actual.compareTo(rangeHigh) <= 0;
        return new TrajectoryPoint(candle.getTs(), actual, deviation, deviationPct, withinRange);
    }
}
