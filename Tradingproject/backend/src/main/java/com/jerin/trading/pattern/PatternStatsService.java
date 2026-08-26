package com.jerin.trading.pattern;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.domain.PatternStats;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import com.jerin.trading.repository.PatternStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes each pattern's historical win rate and average move size from stored candles —
 * this is the "compute conditional win rate from backfilled data" step (MVP), not the full
 * walk-forward backtest engine (that's Phase 4, deliberately deferred until MVP has run and
 * logged real outcomes for a while).
 */
@Service
public class PatternStatsService {

    /** Bars held after a pattern fires before checking the outcome — shared with OutcomeEvaluationService. */
    public static final int HOLDING_PERIOD_BARS = 5;

    private final OhlcvCandleRepository candleRepository;
    private final PatternStatsRepository patternStatsRepository;
    private final List<Pattern> patterns;

    public PatternStatsService(OhlcvCandleRepository candleRepository,
                                PatternStatsRepository patternStatsRepository, List<Pattern> patterns) {
        this.candleRepository = candleRepository;
        this.patternStatsRepository = patternStatsRepository;
        this.patterns = patterns;
    }

    @Transactional
    public List<PatternStats> recomputeAll(Instrument instrument, String interval) {
        List<OhlcvCandle> candles = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), interval);
        if (candles.size() < HOLDING_PERIOD_BARS + 2) {
            return List.of();
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        PatternContext context = new PatternContext(
                candles,
                EmaCalculator.calculate(closes, 9),
                EmaCalculator.calculate(closes, 21),
                RsiCalculator.calculate(closes, 14));

        OffsetDateTime now = OffsetDateTime.now();
        List<PatternStats> results = new ArrayList<>();
        for (Pattern pattern : patterns) {
            results.add(computeAndSave(pattern, candles, context, now));
        }
        return results;
    }

    private PatternStats computeAndSave(Pattern pattern, List<OhlcvCandle> candles, PatternContext context,
                                         OffsetDateTime now) {
        int occurrences = 0;
        int wins = 0;
        double movePctSum = 0;

        int lastUsableIndex = candles.size() - HOLDING_PERIOD_BARS;
        for (int i = 0; i < lastUsableIndex; i++) {
            if (!pattern.firesAt(i, context)) {
                continue;
            }
            occurrences++;
            BigDecimal entryClose = candles.get(i).getClose();
            BigDecimal exitClose = candles.get(i + HOLDING_PERIOD_BARS).getClose();
            double movePct = exitClose.subtract(entryClose)
                    .divide(entryClose, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            boolean won = pattern.direction() == PatternDirection.UP ? movePct > 0 : movePct < 0;
            if (won) {
                wins++;
            }
            movePctSum += movePct;
        }

        PatternStats stats = PatternStats.builder()
                .patternId(pattern.id())
                .windowStart(candles.get(0).getTs().toLocalDate())
                .windowEnd(candles.get(candles.size() - 1).getTs().toLocalDate())
                .sampleSize(occurrences)
                .winRate(occurrences == 0 ? null
                        : BigDecimal.valueOf((double) wins / occurrences * 100).setScale(2, RoundingMode.HALF_UP))
                .avgMovePct(occurrences == 0 ? null
                        : BigDecimal.valueOf(movePctSum / occurrences).setScale(3, RoundingMode.HALF_UP))
                .lastRecalibrated(now)
                .build();
        return patternStatsRepository.save(stats);
    }
}
