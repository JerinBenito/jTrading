package com.jerin.trading.signal;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.domain.SignalOutcome;
import com.jerin.trading.domain.SignalPrediction;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.pattern.PatternStatsService;
import com.jerin.trading.repository.OhlcvCandleRepository;
import com.jerin.trading.repository.SignalOutcomeRepository;
import com.jerin.trading.repository.SignalPredictionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills in outcomes for past predictions once enough bars have elapsed — outcome logging
 * starts from day one per project constraints, well before the full recalibration loop exists.
 */
@Service
public class OutcomeEvaluationService {

    private final SignalPredictionRepository predictionRepository;
    private final SignalOutcomeRepository outcomeRepository;
    private final OhlcvCandleRepository candleRepository;

    public OutcomeEvaluationService(SignalPredictionRepository predictionRepository,
                                     SignalOutcomeRepository outcomeRepository,
                                     OhlcvCandleRepository candleRepository) {
        this.predictionRepository = predictionRepository;
        this.outcomeRepository = outcomeRepository;
        this.candleRepository = candleRepository;
    }

    @Transactional
    public int evaluatePending(Instrument instrument, String interval) {
        List<SignalPrediction> predictions = predictionRepository.findByInstrumentOrderByTsDesc(instrument.name());
        List<OhlcvCandle> candles = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), interval);

        Map<OffsetDateTime, Integer> tsToIndex = new HashMap<>();
        for (int i = 0; i < candles.size(); i++) {
            tsToIndex.put(candles.get(i).getTs(), i);
        }

        int evaluated = 0;
        for (SignalPrediction prediction : predictions) {
            if (outcomeRepository.findByPredictionId(prediction.getId()).isPresent()) {
                continue;
            }
            Integer entryIndex = tsToIndex.get(prediction.getTs());
            if (entryIndex == null) {
                continue;
            }
            int exitIndex = entryIndex + PatternStatsService.HOLDING_PERIOD_BARS;
            if (exitIndex >= candles.size()) {
                continue;
            }

            BigDecimal entryClose = candles.get(entryIndex).getClose();
            BigDecimal exitClose = candles.get(exitIndex).getClose();
            double movePct = exitClose.subtract(entryClose)
                    .divide(entryClose, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
            String actualDirection = movePct > 0 ? "up" : movePct < 0 ? "down" : "neutral";

            outcomeRepository.save(SignalOutcome.builder()
                    .predictionId(prediction.getId())
                    .actualDirection(actualDirection)
                    .actualMovePct(BigDecimal.valueOf(movePct).setScale(3, RoundingMode.HALF_UP))
                    .evaluatedAt(OffsetDateTime.now())
                    .build());
            evaluated++;
        }
        return evaluated;
    }
}
