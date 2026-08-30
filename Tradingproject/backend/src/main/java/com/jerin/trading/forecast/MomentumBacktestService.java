package com.jerin.trading.forecast;

import com.jerin.trading.domain.OhlcvCandle;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.OhlcvCandleRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase C, first test: does past ~2-month return predict the *next* ~2-month return
 * ("time-series momentum" — a well-documented, widely-replicated finding at multi-month
 * horizons in the finance literature, and genuinely different from the short-horizon
 * random-walk behavior already confirmed three separate ways for this system's hourly/daily
 * work). Deliberately descriptive (correlation + quartile buckets), not a fitted regression —
 * matches the "deterministic first, don't overfit a small sample" discipline.
 *
 * Built from daily bars aggregated from the same hourly candles already ingested — no new
 * data source. Honest caveat: at ~487 daily bars, this horizon has a far smaller *effective*
 * sample than the hourly/daily work (thousands of independent-ish samples there) — walk-forward
 * windows here overlap heavily and are highly autocorrelated, so `nonOverlappingSampleSize` is
 * reported alongside the raw walk-forward count as the more honest basis for judging significance.
 */
@Service
public class MomentumBacktestService {

    static final int LOOKBACK_DAYS = 40;
    static final int HORIZON_DAYS = 40;
    private static final String SOURCE_INTERVAL = "1h";

    private final OhlcvCandleRepository candleRepository;

    public MomentumBacktestService(OhlcvCandleRepository candleRepository) {
        this.candleRepository = candleRepository;
    }

    public MomentumBacktestResult compare(Instrument instrument) {
        List<OhlcvCandle> hourly = candleRepository.findByInstrumentAndIntervalOrderByTsAsc(instrument.name(), SOURCE_INTERVAL);
        List<OhlcvCandle> daily = DailyBarAggregator.aggregate(hourly);
        List<Double> closes = daily.stream().map(c -> c.getClose().doubleValue()).toList();

        List<Double> pastReturns = new ArrayList<>();
        List<Double> futureReturns = new ArrayList<>();

        for (int i = LOOKBACK_DAYS; i + HORIZON_DAYS < closes.size(); i++) {
            double past = (closes.get(i) - closes.get(i - LOOKBACK_DAYS)) / closes.get(i - LOOKBACK_DAYS);
            double future = (closes.get(i + HORIZON_DAYS) - closes.get(i)) / closes.get(i);
            pastReturns.add(past);
            futureReturns.add(future);
        }

        if (pastReturns.size() < 20) {
            return new MomentumBacktestResult(LOOKBACK_DAYS, HORIZON_DAYS, pastReturns.size(), 0, BigDecimal.ZERO, List.of());
        }

        double correlation = MomentumCorrelationCalculator.pearsonCorrelation(pastReturns, futureReturns);
        int nonOverlapping = closes.size() / HORIZON_DAYS;

        List<MomentumBucketResult> buckets = MomentumQuartileBucketer.build(pastReturns, futureReturns);

        return new MomentumBacktestResult(
                LOOKBACK_DAYS, HORIZON_DAYS,
                pastReturns.size(), nonOverlapping,
                BigDecimal.valueOf(correlation).setScale(4, RoundingMode.HALF_UP),
                buckets);
    }
}
