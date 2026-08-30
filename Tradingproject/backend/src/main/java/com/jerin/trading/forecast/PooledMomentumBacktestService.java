package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.ingestion.Nifty50Constituents;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Phase C momentum test, pooled across a basket of instruments (NIFTY, BANKNIFTY, and whichever
 * NIFTY 50 constituents have been backfilled — see {@link com.jerin.trading.ingestion.EquityBasketIngestionService})
 * instead of a single instrument at a time, per the user's own suggestion for boosting the
 * cross-sectional sample size beyond what NIFTY/BANKNIFTY's ~2-year history alone can provide
 * (only ~12 non-overlapping windows there). See {@link MomentumBacktestService} for the
 * single-instrument version and its caveats, which still apply per-instrument here.
 */
@Service
public class PooledMomentumBacktestService {

    private static final int LOOKBACK_DAYS = MomentumBacktestService.LOOKBACK_DAYS;
    private static final int HORIZON_DAYS = MomentumBacktestService.HORIZON_DAYS;
    private static final String SOURCE_INTERVAL = "1h";
    private static final int MIN_DAILY_BARS = LOOKBACK_DAYS + HORIZON_DAYS + 20;

    private final OhlcvCandleRepository candleRepository;

    public PooledMomentumBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public PooledMomentumBacktestResult compare() {
        List<String> instrumentTags = Stream.concat(
                Stream.of(Instrument.NIFTY.name(), Instrument.BANKNIFTY.name()),
                Nifty50Constituents.SYMBOLS.stream()
        ).toList();

        List<Double> pastReturns = new ArrayList<>();
        List<Double> futureReturns = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int instrumentsUsed = 0;
        int nonOverlappingTotal = 0;

        for (String tag : instrumentTags) {
            List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(tag, SOURCE_INTERVAL);
            List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);
            List<Double> closes = daily.stream().map(c -> c.getClose().doubleValue()).toList();

            if (closes.size() < MIN_DAILY_BARS) {
                skipped.add(tag);
                continue;
            }

            for (int i = LOOKBACK_DAYS; i + HORIZON_DAYS < closes.size(); i++) {
                double past = (closes.get(i) - closes.get(i - LOOKBACK_DAYS)) / closes.get(i - LOOKBACK_DAYS);
                double future = (closes.get(i + HORIZON_DAYS) - closes.get(i)) / closes.get(i);
                pastReturns.add(past);
                futureReturns.add(future);
            }
            instrumentsUsed++;
            nonOverlappingTotal += closes.size() / HORIZON_DAYS;
        }

        if (pastReturns.size() < 20) {
            return new PooledMomentumBacktestResult(LOOKBACK_DAYS, HORIZON_DAYS, instrumentsUsed, skipped,
                    pastReturns.size(), nonOverlappingTotal, BigDecimal.ZERO, List.of());
        }

        double correlation = MomentumCorrelationCalculator.pearsonCorrelation(pastReturns, futureReturns);
        List<MomentumBucketResult> buckets = MomentumQuartileBucketer.build(pastReturns, futureReturns);

        return new PooledMomentumBacktestResult(
                LOOKBACK_DAYS, HORIZON_DAYS, instrumentsUsed, skipped,
                pastReturns.size(), nonOverlappingTotal,
                BigDecimal.valueOf(correlation).setScale(4, RoundingMode.HALF_UP),
                buckets);
    }
}
