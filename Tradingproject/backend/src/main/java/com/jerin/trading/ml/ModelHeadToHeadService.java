package com.jerin.trading.ml;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.repository.HourlyPredictionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared "which live track was closer to actual, per shared evaluated day" comparison — used by
 * both the model leaderboard shown in the app and {@link AdaptiveSelectionService}'s trailing-
 * window pick. One place for this logic so both stay consistent.
 */
@Service
public class ModelHeadToHeadService {

    private static final String DAILY_INTERVAL = "1d";
    private static final String INTRADAY_HORIZON = "INTRADAY";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final HourlyPredictionRepository hourlyPredictionRepository;
    private final AiPredictionRepository aiPredictionRepository;

    public ModelHeadToHeadService(HourlyPredictionRepository hourlyPredictionRepository,
                                   AiPredictionRepository aiPredictionRepository) {
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.aiPredictionRepository = aiPredictionRepository;
    }

    /** Most recent {@code limit} days (or fewer) where both tracks have an evaluated same-day
     * prediction for this instrument, newest first. Rows with a null predictedPrice/actualPrice
     * (predating the V8 price-field migration) are skipped rather than crashing. */
    public List<HeadToHeadDay> recentSharedDays(String instrument, int limit) {
        List<HourlyPrediction> deterministicEvaluated = hourlyPredictionRepository.findRecentEvaluated(instrument, DAILY_INTERVAL);
        Map<LocalDate, BigDecimal> deterministicErrorByDate = new HashMap<>();
        for (HourlyPrediction p : deterministicEvaluated) {
            LocalDate d = p.getPredictedForTs().atZoneSameInstant(IST).toLocalDate();
            deterministicErrorByDate.put(d, p.getActualClose().subtract(p.getPredictedClose()).abs());
        }

        List<AiPrediction> aiEvaluated = aiPredictionRepository.findRecentEvaluated(instrument, INTRADAY_HORIZON);
        List<HeadToHeadDay> days = new ArrayList<>();
        for (AiPrediction p : aiEvaluated) {
            if (days.size() >= limit) {
                break;
            }
            BigDecimal detError = deterministicErrorByDate.get(p.getTargetDate());
            if (detError == null || p.getActualPrice() == null || p.getPredictedPrice() == null) {
                continue;
            }
            BigDecimal aiError = p.getActualPrice().subtract(p.getPredictedPrice()).abs();
            days.add(new HeadToHeadDay(p.getTargetDate(), detError, aiError));
        }
        return days;
    }
}
