package com.jerin.trading.pattern;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.indicator.EmaCalculator;
import com.jerin.trading.indicator.RsiCalculator;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Segments each pattern's historical occurrences by trading volume at the moment it fired —
 * "high" (≥1.5x the trailing 20-bar average) vs "normal" vs "unknown" (missing/zero volume,
 * which is common for index instruments like NIFTY/BANKNIFTY — index volume isn't always
 * meaningful the way it is for an individual stock) — and compares win rates per bucket. Answers
 * a real question rather than assuming volume confirmation helps: does it actually improve
 * these specific patterns on these specific instruments?
 */
@Service
public class VolumeConfirmationBacktestService {

    private static final int VOLUME_LOOKBACK = 20;
    private static final double HIGH_VOLUME_RATIO = 1.5;

    private final OhlcvCandleRepository candleRepository;
    private final List<Pattern> patterns;

    public VolumeConfirmationBacktestService(OhlcvCandleRepository candleRepository, List<Pattern> patterns) {
        this.candleRepository = candleRepository;
        this.patterns = patterns;
    }

    public List<VolumeConfirmationResult> compare(Instrument instrument, String interval) {
        return compare(instrument.name(), interval);
    }

    /** Same as {@link #compare(Instrument, String)}, for an instrument tag outside the fixed enum (e.g. "NIFTY_FUT"). */
    public List<VolumeConfirmationResult> compare(String instrumentTag, String interval) {
        List<OhlcvCandle> candles = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrumentTag, interval);
        if (candles.size() < PatternStatsService.HOLDING_PERIOD_BARS + VOLUME_LOOKBACK + 2) {
            return List.of();
        }

        List<BigDecimal> closes = candles.stream().map(OhlcvCandle::getClose).toList();
        PatternContext context = new PatternContext(
                candles,
                EmaCalculator.calculate(closes, 9),
                EmaCalculator.calculate(closes, 21),
                RsiCalculator.calculate(closes, 14));

        List<VolumeConfirmationResult> results = new ArrayList<>();
        for (Pattern pattern : patterns) {
            results.addAll(evaluate(pattern, candles, context));
        }
        return results;
    }

    private List<VolumeConfirmationResult> evaluate(Pattern pattern, List<OhlcvCandle> candles, PatternContext context) {
        Map<String, Accumulator> byBucket = new LinkedHashMap<>();
        byBucket.put("HIGH", new Accumulator());
        byBucket.put("NORMAL", new Accumulator());
        byBucket.put("UNKNOWN", new Accumulator());

        int lastUsableIndex = candles.size() - PatternStatsService.HOLDING_PERIOD_BARS;
        for (int i = VOLUME_LOOKBACK; i < lastUsableIndex; i++) {
            if (!pattern.firesAt(i, context)) {
                continue;
            }
            String bucket = volumeBucket(candles, i);

            BigDecimal entryClose = candles.get(i).getClose();
            BigDecimal exitClose = candles.get(i + PatternStatsService.HOLDING_PERIOD_BARS).getClose();
            double movePct = exitClose.subtract(entryClose)
                    .divide(entryClose, 6, RoundingMode.HALF_UP).doubleValue() * 100;
            boolean won = pattern.direction() == PatternDirection.UP ? movePct > 0 : movePct < 0;

            byBucket.get(bucket).record(won, movePct);
        }

        List<VolumeConfirmationResult> results = new ArrayList<>();
        for (Map.Entry<String, Accumulator> entry : byBucket.entrySet()) {
            results.add(entry.getValue().toResult(pattern.id(), entry.getKey()));
        }
        return results;
    }

    private String volumeBucket(List<OhlcvCandle> candles, int index) {
        Long currentVolume = candles.get(index).getVolume();
        if (currentVolume == null || currentVolume <= 0) {
            return "UNKNOWN";
        }
        long sum = 0;
        int count = 0;
        for (int j = index - VOLUME_LOOKBACK; j < index; j++) {
            Long v = candles.get(j).getVolume();
            if (v != null && v > 0) {
                sum += v;
                count++;
            }
        }
        if (count == 0) {
            return "UNKNOWN";
        }
        double avgVolume = (double) sum / count;
        double ratio = currentVolume / avgVolume;
        return ratio >= HIGH_VOLUME_RATIO ? "HIGH" : "NORMAL";
    }

    private static final class Accumulator {
        private int n = 0;
        private int wins = 0;
        private double movePctSum = 0;

        void record(boolean won, double movePct) {
            n++;
            if (won) {
                wins++;
            }
            movePctSum += movePct;
        }

        VolumeConfirmationResult toResult(String patternId, String bucket) {
            if (n == 0) {
                return new VolumeConfirmationResult(patternId, bucket, 0, null, null);
            }
            return new VolumeConfirmationResult(
                    patternId,
                    bucket,
                    n,
                    BigDecimal.valueOf((double) wins / n * 100).setScale(2, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(movePctSum / n).setScale(3, RoundingMode.HALF_UP));
        }
    }
}
