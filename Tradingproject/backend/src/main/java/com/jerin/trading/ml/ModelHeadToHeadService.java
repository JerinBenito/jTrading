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
import java.util.Optional;

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
    private final AiPredictionSnapshotRepository aiPredictionSnapshotRepository;

    public ModelHeadToHeadService(HourlyPredictionRepository hourlyPredictionRepository,
                                   AiPredictionRepository aiPredictionRepository,
                                   AiPredictionSnapshotRepository aiPredictionSnapshotRepository) {
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.aiPredictionRepository = aiPredictionRepository;
        this.aiPredictionSnapshotRepository = aiPredictionSnapshotRepository;
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
            if (detError == null) {
                continue;
            }
            // Compare the deterministic model's single market-open forecast against the AI's own
            // FIRST call of the day (same information time), not the latest AiPrediction row -
            // that one is continuously overwritten through the day and trivially converges toward
            // the close, which would hand the AI a free win for having seen more of the day, not
            // for being a better model. Found 2026-09-27: this was silently deciding both the
            // leaderboard and the adaptive pick before the fix.
            Optional<AiPredictionSnapshot> firstCall = aiPredictionSnapshotRepository
                    .findFirstOfDay(instrument, INTRADAY_HORIZON, p.getTargetDate());
            if (firstCall.isEmpty() || firstCall.get().isAfterClose() || firstCall.get().getErrorAbs() == null) {
                continue;
            }
            days.add(new HeadToHeadDay(p.getTargetDate(), detError, firstCall.get().getErrorAbs()));
        }
        return days;
    }
}
