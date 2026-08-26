package com.jerin.trading.signal;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.domain.PatternStats;
import com.jerin.trading.domain.SignalPrediction;
import com.jerin.trading.indicator.AtrCalculator;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.pattern.Pattern;
import com.jerin.trading.pattern.PatternContext;
import com.jerin.trading.pattern.PatternDirection;
import com.jerin.trading.domain.SignalOutcome;
import com.jerin.trading.repository.OhlcvCandleRepository;
import com.jerin.trading.repository.PatternStatsRepository;
import com.jerin.trading.repository.SignalOutcomeRepository;
import com.jerin.trading.repository.SignalPredictionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates live signals from patterns firing on the latest bar. A signal is only ever
 * emitted if a pattern already has real historical stats (PatternStatsService must have run
 * at least once) — never fabricate a confidence tier with no backing sample.
 */
@Service
public class SignalService {

    /** Below this, a pattern's win rate isn't trusted enough to call it "high"/"medium" confidence. */
    private static final int MIN_SAMPLE_SIZE = 30;
    private static final BigDecimal HIGH_CONFIDENCE_WIN_RATE = BigDecimal.valueOf(65);
    private static final BigDecimal MEDIUM_CONFIDENCE_WIN_RATE = BigDecimal.valueOf(55);

    private final OhlcvCandleRepository candleRepository;
    private final PatternStatsRepository patternStatsRepository;
    private final SignalPredictionRepository signalPredictionRepository;
    private final SignalOutcomeRepository signalOutcomeRepository;
    private final List<Pattern> patterns;

    public SignalService(OhlcvCandleRepository candleRepository, PatternStatsRepository patternStatsRepository,
                          SignalPredictionRepository signalPredictionRepository,
                          SignalOutcomeRepository signalOutcomeRepository, List<Pattern> patterns) {
        this.candleRepository = candleRepository;
        this.patternStatsRepository = patternStatsRepository;
        this.signalPredictionRepository = signalPredictionRepository;
        this.signalOutcomeRepository = signalOutcomeRepository;
        this.patterns = patterns;
    }

    /** Past predictions (most recent first) with their outcome if evaluated yet — read-only, triggers nothing. */
    public List<SignalHistoryEntry> getHistory(Instrument instrument) {
        return signalPredictionRepository.findByInstrumentOrderByTsDesc(instrument.name()).stream()
                .map(prediction -> {
                    Optional<SignalOutcome> outcome = signalOutcomeRepository.findByPredictionId(prediction.getId());
                    return new SignalHistoryEntry(
                            prediction.getId(),
                            prediction.getInstrument(),
                            prediction.getTs(),
                            prediction.getPatternId(),
                            prediction.getPredictedDirection(),
                            prediction.getConfidenceTier(),
                            prediction.getSampleSizeAtTime(),
                            outcome.map(SignalOutcome::getActualDirection).orElse(null),
                            outcome.map(SignalOutcome::getActualMovePct).orElse(null));
                })
                .toList();
    }

    @Transactional
    public List<SignalResponse> generateSignals(Instrument instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository
                .findTop200ByInstrumentAndIntervalOrderByTsDesc(instrument.name(), interval);
        Collections.reverse(candles);
        if (candles.isEmpty()) {
            return List.of();
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        List<BigDecimal> ema9 = EmaCalculator.calculate(closes, 9);
        List<BigDecimal> ema21 = EmaCalculator.calculate(closes, 21);
        List<BigDecimal> rsi14 = RsiCalculator.calculate(closes, 14);
        List<BigDecimal> atr14 = AtrCalculator.calculate(candles, 14);
        PatternContext context = new PatternContext(candles, ema9, ema21, rsi14);

        int lastIndex = candles.size() - 1;
        OhlcvCandle latestCandle = candles.get(lastIndex);
        BigDecimal latestAtr = atr14.get(lastIndex);

        List<SignalResponse> generated = new ArrayList<>();
        for (Pattern pattern : patterns) {
            if (!pattern.firesAt(lastIndex, context)) {
                continue;
            }

            if (signalPredictionRepository.existsByInstrumentAndPatternIdAndTs(
                    instrument.name(), pattern.id(), latestCandle.getTs())) {
                continue; // already logged this exact pattern+bar (e.g. a manual check overlapping the scheduled job)
            }

            List<PatternStats> history = patternStatsRepository.findByPatternIdOrderByWindowEndDesc(pattern.id());
            if (history.isEmpty()) {
                continue;
            }
            PatternStats latestStats = history.get(0);
            if (latestStats.getSampleSize() == null || latestStats.getSampleSize() == 0) {
                continue;
            }

            String confidenceTier = confidenceTier(latestStats);

            Map<String, Object> inputsSnapshot = new LinkedHashMap<>();
            inputsSnapshot.put("close", latestCandle.getClose());
            inputsSnapshot.put("ema9", ema9.get(lastIndex));
            inputsSnapshot.put("ema21", ema21.get(lastIndex));
            inputsSnapshot.put("rsi14", rsi14.get(lastIndex));
            inputsSnapshot.put("atr14", latestAtr);
            inputsSnapshot.put("patternWinRate", latestStats.getWinRate());
            inputsSnapshot.put("patternSampleSize", latestStats.getSampleSize());

            SignalPrediction prediction = signalPredictionRepository.save(SignalPrediction.builder()
                    .instrument(instrument.name())
                    .ts(latestCandle.getTs())
                    .patternId(pattern.id())
                    .inputsSnapshot(inputsSnapshot)
                    .predictedDirection(pattern.direction() == PatternDirection.UP ? "up" : "down")
                    .confidenceTier(confidenceTier)
                    .sampleSizeAtTime(latestStats.getSampleSize())
                    .build());

            BigDecimal range = latestAtr != null ? latestAtr : BigDecimal.ZERO;
            generated.add(new SignalResponse(
                    prediction.getId(),
                    instrument.name(),
                    pattern.id(),
                    prediction.getPredictedDirection(),
                    confidenceTier,
                    latestStats.getSampleSize(),
                    latestCandle.getClose().subtract(range),
                    latestCandle.getClose().add(range)));
        }
        return generated;
    }

    private String confidenceTier(PatternStats stats) {
        if (stats.getSampleSize() < MIN_SAMPLE_SIZE || stats.getWinRate() == null) {
            return "low";
        }
        if (stats.getWinRate().compareTo(HIGH_CONFIDENCE_WIN_RATE) >= 0) {
            return "high";
        }
        if (stats.getWinRate().compareTo(MEDIUM_CONFIDENCE_WIN_RATE) >= 0) {
            return "medium";
        }
        return "low";
    }
}
