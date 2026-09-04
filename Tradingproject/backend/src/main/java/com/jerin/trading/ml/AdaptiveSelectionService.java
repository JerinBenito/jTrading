package com.jerin.trading.ml;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.repository.HourlyPredictionRepository;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * NOT a validated model — an observation-only experiment, same spirit as {@link AiPredictionService}
 * when it first launched. Picks, per instrument, whichever of the two live same-day-close tracks
 * (DETERMINISTIC vs the raw AI/LightGBM prediction) has won more of its trailing evaluated shared
 * days, and records that pick as its own ledger entry (horizon {@code INTRADAY_ADAPTIVE}) so it
 * accumulates a real, scored track record before anyone treats it as trustworthy.
 *
 * As of 2026-09-04 there are only ~4 evaluated shared days total across the whole ledger —
 * nowhere near enough to know whether "lean toward the recent winner" beats either fixed
 * strategy alone. This service exists to start collecting that evidence, not to claim it
 * already has any. Defaults to the deterministic model (the longer-proven track) whenever
 * there isn't yet enough shared history to say either way.
 */
@Service
public class AdaptiveSelectionService {

    private static final String DAILY_INTERVAL = "1d";
    private static final String HOURLY_INTERVAL = "1h";
    private static final String AI_INTRADAY_HORIZON = "INTRADAY";
    private static final String ADAPTIVE_HORIZON = "INTRADAY_ADAPTIVE";
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int TRAILING_WINDOW = 10;

    private final HourlyPredictionRepository hourlyPredictionRepository;
    private final AiPredictionRepository aiPredictionRepository;
    private final AiPredictionService aiPredictionService;
    private final ModelHeadToHeadService headToHeadService;
    private final OhlcvCandleRepository candleRepository;

    public AdaptiveSelectionService(HourlyPredictionRepository hourlyPredictionRepository,
                                     AiPredictionRepository aiPredictionRepository,
                                     AiPredictionService aiPredictionService,
                                     ModelHeadToHeadService headToHeadService,
                                     OhlcvCandleRepository candleRepository) {
        this.hourlyPredictionRepository = hourlyPredictionRepository;
        this.aiPredictionRepository = aiPredictionRepository;
        this.aiPredictionService = aiPredictionService;
        this.headToHeadService = headToHeadService;
        this.candleRepository = candleRepository;
    }

    /** Safe to call every ingestion cycle — no-ops until both source predictions exist for
     * today, and upserts (via {@link AiPredictionService#recordPrediction}) after that so a
     * later cycle can't create a duplicate. */
    public void recordTodayPrediction(String instrument) {
        LocalDate today = OffsetDateTime.now().atZoneSameInstant(IST).toLocalDate();

        OffsetDateTime dayStart = today.atStartOfDay(IST).toOffsetDateTime();
        OffsetDateTime dayEnd = today.plusDays(1).atStartOfDay(IST).toOffsetDateTime();
        List<OhlcvCandle> dayCandles = candleRepository.findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
                instrument, HOURLY_INTERVAL, dayStart, dayEnd);
        if (dayCandles.isEmpty()) {
            return;
        }

        Optional<HourlyPrediction> deterministic = hourlyPredictionRepository
                .findByInstrumentAndIntervalAndPredictedForTs(instrument, DAILY_INTERVAL, dayCandles.get(0).getTs());
        Optional<AiPrediction> ai = aiPredictionRepository
                .findByInstrumentAndHorizonAndTargetDate(instrument, AI_INTRADAY_HORIZON, today);
        if (deterministic.isEmpty() || ai.isEmpty() || ai.get().getPredictedPrice() == null) {
            return; // nothing to choose between yet today
        }

        BigDecimal deterministicPrice = deterministic.get().getPredictedClose();
        BigDecimal aiPrice = ai.get().getPredictedPrice();
        boolean pickAi = aiHasWonMoreRecently(instrument);
        BigDecimal chosen = pickAi ? aiPrice : deterministicPrice;

        // baseline = the deterministic price either way, so "betterThanBaseline" (computed
        // generically once this is evaluated) answers a clean question: did picking adaptively
        // actually beat just always trusting the deterministic model?
        aiPredictionService.recordPrediction(instrument, ADAPTIVE_HORIZON, "PRICE",
                "adaptive-v1-" + (pickAi ? "ai" : "deterministic"),
                chosen, deterministicPrice, today, chosen, deterministicPrice);
    }

    private boolean aiHasWonMoreRecently(String instrument) {
        List<HeadToHeadDay> days = headToHeadService.recentSharedDays(instrument, TRAILING_WINDOW);
        long aiWins = days.stream().filter(HeadToHeadDay::aiWon).count();
        long deterministicWins = days.stream().filter(HeadToHeadDay::deterministicWon).count();
        return aiWins > deterministicWins;
    }
}
