package com.jerin.trading.comparison;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.ml.AiPrediction;
import com.jerin.trading.ml.AiPredictionRepository;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls together every model's prediction for one instrument+day into one normalized list — the
 * deterministic same-day model ({@link com.jerin.trading.forecast.DailyForecastPredictionService})
 * plus every AI horizon ({@link com.jerin.trading.ml.AiPredictionService}) — so the mobile app can
 * show them side by side instead of the user having to piece it together from separate endpoints.
 */
@Service
public class PredictionComparisonService {

    private static final String DAILY_INTERVAL = "1d";
    private static final String HOURLY_INTERVAL = "1h";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> FORWARD_HORIZONS = List.of("FORWARD_5D", "FORWARD_10D", "FORWARD_20D", "FORWARD_40D");

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository hourlyPredictionRepository;
    private final AiPredictionRepository aiPredictionRepository;

    public PredictionComparisonService(OhlcvCandleRepository candleRepository,
                                        HourlyPredictionRepository hourlyPredictionRepository,
                                        AiPredictionRepository aiPredictionRepository) {
        this.candleRepository = candleRepository;
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.aiPredictionRepository = aiPredictionRepository;
    }

    public PredictionComparisonResponse compare(String instrument, LocalDate date) {
        LocalDate targetDate = date != null ? date : OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        List<PredictionComparisonRow> rows = new ArrayList<>();

        addDeterministicRow(instrument, targetDate, rows);

        aiPredictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, "INTRADAY", targetDate)
                .ifPresent(p -> rows.add(toAiRow(p, "AI same-day close")));

        for (String horizon : FORWARD_HORIZONS) {
            aiPredictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, horizon, targetDate)
                    .ifPresent(p -> rows.add(toAiRow(p, "AI forward " + horizonLabel(horizon))));
        }

        return new PredictionComparisonResponse(instrument, targetDate, rows);
    }

    private void addDeterministicRow(String instrument, LocalDate targetDate, List<PredictionComparisonRow> rows) {
        OffsetDateTime dayStart = targetDate.atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDate.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        List<OhlcvCandle> dayCandles = candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                instrument, HOURLY_INTERVAL, dayStart, dayEnd);
        if (dayCandles.isEmpty()) {
            return;
        }
        hourlyPredictionRepository.findByInstrumentAndIntervalAndPredictedForTs(instrument, DAILY_INTERVAL, dayCandles.get(0).getTs())
                .ifPresent(p -> rows.add(toDeterministicRow(p, targetDate)));
    }

    private PredictionComparisonRow toDeterministicRow(HourlyPrediction p, LocalDate targetDate) {
        return new PredictionComparisonRow(
                "DETERMINISTIC", "Same-day close (random walk + bias correction)", p.getModelName(), targetDate,
                p.getPredictedClose(), p.getRangeLow(), p.getRangeHigh(), null, p.getActualClose(),
                p.getActualClose() != null, null, null);
    }

    private PredictionComparisonRow toAiRow(AiPrediction p, String label) {
        return new PredictionComparisonRow(
                "AI", label, p.getModelVersion(), p.getTargetDate(),
                p.getPredictedPrice(), null, null, p.getBaselinePrice(), p.getActualPrice(),
                p.getActualValue() != null, p.getBetterThanBaseline(), p.getDirectionCorrect());
    }

    private String horizonLabel(String horizon) {
        return switch (horizon) {
            case "FORWARD_5D" -> "5 trading days";
            case "FORWARD_10D" -> "10 trading days";
            case "FORWARD_20D" -> "20 trading days";
            case "FORWARD_40D" -> "40 trading days";
            default -> horizon;
        };
    }
}
