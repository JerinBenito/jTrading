package com.jerin.trading.comparison;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.ml.AiPrediction;
import com.jerin.trading.ml.AiPredictionRepository;
import com.jerin.trading.ml.HeadToHeadDay;
import com.jerin.trading.ml.ModelHeadToHeadService;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private static final String INTRADAY_HORIZON = "INTRADAY";
    private static final String ADAPTIVE_HORIZON = "INTRADAY_ADAPTIVE";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final List<String> FORWARD_HORIZONS = List.of("FORWARD_5D", "FORWARD_10D", "FORWARD_20D", "FORWARD_40D");
    private static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH);
    private static final int MIN_SAMPLES_FOR_AI_BAND = 5;
    private static final int MAX_SAMPLES_FOR_AI_BAND = 30;
    private static final int LEADERBOARD_LOOKBACK = 30;

    private final OhlcvCandleRepository candleRepository;
    private final HourlyPredictionRepository hourlyPredictionRepository;
    private final AiPredictionRepository aiPredictionRepository;
    private final ModelHeadToHeadService headToHeadService;

    public PredictionComparisonService(OhlcvCandleRepository candleRepository,
                                        HourlyPredictionRepository hourlyPredictionRepository,
                                        AiPredictionRepository aiPredictionRepository,
                                        ModelHeadToHeadService headToHeadService) {
        this.candleRepository = candleRepository;
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.aiPredictionRepository = aiPredictionRepository;
        this.headToHeadService = headToHeadService;
    }

    public PredictionComparisonResponse compare(String instrument, LocalDate date) {
        LocalDate targetDate = date != null ? date : OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();
        List<PredictionComparisonRow> rows = new ArrayList<>();

        OffsetDateTime dayStart = targetDate.atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = targetDate.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        List<OhlcvCandle> dayCandles = candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                instrument, HOURLY_INTERVAL, dayStart, dayEnd);
        BigDecimal currentPrice = dayCandles.isEmpty() ? null : dayCandles.get(dayCandles.size() - 1).getClose();

        addDeterministicRow(instrument, targetDate, dayCandles, currentPrice, rows);
        addHourlyRows(instrument, dayCandles, rows);

        aiPredictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, INTRADAY_HORIZON, targetDate)
                .ifPresent(p -> rows.add(toAiRow(p, "AI same-day close", currentPrice)));

        aiPredictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, ADAPTIVE_HORIZON, targetDate)
                .ifPresent(p -> rows.add(toAiRow(p, "AI (adaptive, unvalidated)", currentPrice)));

        for (String horizon : FORWARD_HORIZONS) {
            aiPredictionRepository.findByInstrumentAndHorizonAndTargetDate(instrument, horizon, targetDate)
                    .ifPresent(p -> rows.add(toAiRow(p, "AI forward " + horizonLabel(horizon), currentPrice)));
        }

        return new PredictionComparisonResponse(instrument, targetDate, rows);
    }

    /**
     * Head-to-head accuracy between the two live tracks over their most recent shared evaluated
     * days — real recorded outcomes only, see {@link ModelLeaderboard}.
     */
    public ModelLeaderboard leaderboard(String instrument) {
        List<HeadToHeadDay> days = headToHeadService.recentSharedDays(instrument, LEADERBOARD_LOOKBACK);
        int deterministicWins = 0;
        int aiWins = 0;
        int ties = 0;
        for (HeadToHeadDay day : days) {
            if (day.aiWon()) {
                aiWins++;
            } else if (day.deterministicWon()) {
                deterministicWins++;
            } else {
                ties++;
            }
        }
        return new ModelLeaderboard(instrument, days.size(), deterministicWins, aiWins, ties);
    }

    private void addDeterministicRow(String instrument, LocalDate targetDate, List<OhlcvCandle> dayCandles,
                                      BigDecimal currentPrice, List<PredictionComparisonRow> rows) {
        if (dayCandles.isEmpty()) {
            return;
        }
        hourlyPredictionRepository.findByInstrumentAndIntervalAndPredictedForTs(instrument, DAILY_INTERVAL, dayCandles.get(0).getTs())
                .ifPresent(p -> rows.add(toDeterministicRow(p, targetDate, currentPrice)));
    }

    /** One row per hour so far today from the original rolling hourly-forecast model — its
     * predicted price alongside that hour's real candle close, which doubles as "current price"
     * and "actual" since the hour has already closed by the time the candle exists. */
    private void addHourlyRows(String instrument, List<OhlcvCandle> dayCandles, List<PredictionComparisonRow> rows) {
        for (OhlcvCandle candle : dayCandles) {
            hourlyPredictionRepository.findByInstrumentAndIntervalAndPredictedForTs(instrument, HOURLY_INTERVAL, candle.getTs())
                    .ifPresent(p -> rows.add(toHourlyRow(p, candle)));
        }
    }

    private PredictionComparisonRow toDeterministicRow(HourlyPrediction p, LocalDate targetDate, BigDecimal currentPrice) {
        return new PredictionComparisonRow(
                "DETERMINISTIC", "Same-day close (random walk + bias correction)", p.getModelName(), targetDate,
                p.getPredictedClose(), p.getRangeLow(), p.getRangeHigh(), null, currentPrice, p.getActualClose(),
                p.getActualClose() != null, null, null);
    }

    private PredictionComparisonRow toHourlyRow(HourlyPrediction p, OhlcvCandle candle) {
        String hourLabel = candle.getTs().atZoneSameInstant(IST).format(HOUR_FORMATTER);
        LocalDate hourDate = candle.getTs().atZoneSameInstant(IST).toLocalDate();
        return new PredictionComparisonRow(
                "DETERMINISTIC", "Hourly forecast — " + hourLabel, p.getModelName(), hourDate,
                p.getPredictedClose(), p.getRangeLow(), p.getRangeHigh(), null, candle.getClose(), candle.getClose(),
                true, null, null);
    }

    private PredictionComparisonRow toAiRow(AiPrediction p, String label, BigDecimal currentPrice) {
        BigDecimal[] band = computeAiErrorBand(p.getInstrument(), p.getHorizon(), p.getPredictedPrice());
        return new PredictionComparisonRow(
                "AI", label, p.getModelVersion(), p.getTargetDate(),
                p.getPredictedPrice(), band != null ? band[0] : null, band != null ? band[1] : null,
                p.getBaselinePrice(), currentPrice, p.getActualPrice(),
                p.getActualValue() != null, p.getBetterThanBaseline(), p.getDirectionCorrect());
    }

    /** A genuine prediction band derived from this model's own recent out-of-sample errors
     * (± average absolute error over up to the last 30 evaluated predictions) — not a fabricated
     * confidence interval. Returns null when fewer than {@link #MIN_SAMPLES_FOR_AI_BAND}
     * evaluated predictions exist yet, rather than guessing. */
    private BigDecimal[] computeAiErrorBand(String instrument, String horizon, BigDecimal predictedPrice) {
        List<AiPrediction> recent = aiPredictionRepository.findRecentEvaluated(instrument, horizon);
        BigDecimal sumAbsError = BigDecimal.ZERO;
        int n = 0;
        for (AiPrediction p : recent) {
            if (n >= MAX_SAMPLES_FOR_AI_BAND) {
                break;
            }
            // predictedPrice/actualPrice can be null on rows predating the V8 price-field
            // migration — skip those rather than let a stale row crash the whole band.
            if (p.getActualPrice() == null || p.getPredictedPrice() == null) {
                continue;
            }
            sumAbsError = sumAbsError.add(p.getActualPrice().subtract(p.getPredictedPrice()).abs());
            n++;
        }
        if (n < MIN_SAMPLES_FOR_AI_BAND) {
            return null;
        }
        BigDecimal avgAbsError = sumAbsError.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
        return new BigDecimal[]{predictedPrice.subtract(avgAbsError), predictedPrice.add(avgAbsError)};
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
