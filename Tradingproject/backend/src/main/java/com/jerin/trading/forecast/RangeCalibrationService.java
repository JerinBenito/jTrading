package com.jerin.trading.forecast;

import com.jerin.trading.domain.RangeCalibrationState;
import com.jerin.trading.repository.RangeCalibrationStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * Persists the online-calibrated ATR multiplier from {@link RangeCalibrator}, one row per
 * instrument+interval — shared by both {@link ForecastPredictionService} (interval "1h") and
 * {@link DailyForecastPredictionService} (interval "1d"), so each gets its own independently
 * converging multiplier. Validated via {@link RangeCalibrationBacktestService} /
 * {@link DailyRangeCalibrationBacktestService} against 2 years of real history before going
 * live (2026-08-26) — real hourly coverage was running under the intended 90% target (NIFTY
 * 85.62%, BANKNIFTY 86.62% with the old fixed ±1x ATR range); the adaptive multiplier corrects
 * that (converging to ~1.29x / ~1.23x in the backtest), while the daily range — already close
 * to target — barely moves.
 */
@Service
public class RangeCalibrationService {

    private final RangeCalibrationStateRepository repository;

    public RangeCalibrationService(RangeCalibrationStateRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public double currentMultiplier(String instrument, String interval) {
        return repository.findByInstrumentAndInterval(instrument, interval)
                .map(state -> state.getMultiplier().doubleValue())
                .orElse(RangeCalibrator.DEFAULT_MULTIPLIER);
    }

    /** One online update step — call once per evaluated prediction, with whether the actual value fell inside the range that was used. */
    @Transactional
    public void recordOutcome(String instrument, String interval, boolean wasCovered) {
        RangeCalibrationState state = repository.findByInstrumentAndInterval(instrument, interval)
                .orElseGet(() -> RangeCalibrationState.builder()
                        .instrument(instrument)
                        .interval(interval)
                        .multiplier(BigDecimal.valueOf(RangeCalibrator.DEFAULT_MULTIPLIER))
                        .updatedAt(OffsetDateTime.now())
                        .build());

        double next = RangeCalibrator.update(state.getMultiplier().doubleValue(), wasCovered);
        state.setMultiplier(BigDecimal.valueOf(next).setScale(4, RoundingMode.HALF_UP));
        state.setUpdatedAt(OffsetDateTime.now());
        repository.save(state);
    }

    public RangeCalibrationStatus status(String instrument, String interval) {
        return repository.findByInstrumentAndInterval(instrument, interval)
                .map(state -> new RangeCalibrationStatus(instrument, interval, state.getMultiplier(), state.getUpdatedAt()))
                .orElse(new RangeCalibrationStatus(instrument, interval,
                        BigDecimal.valueOf(RangeCalibrator.DEFAULT_MULTIPLIER).setScale(4, RoundingMode.HALF_UP), null));
    }
}
