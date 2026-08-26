package com.jerin.trading.forecast;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.ingestion.Instrument;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Manual triggers for the same-day open-to-close forecast — the scheduled job does this
 * automatically every cycle (idempotent: records once per day, evaluates once the day is
 * over), these are for testing on demand. Read history via the existing
 * {@code GET /api/forecast/{instrument}/history?interval=1d}, same table as the hourly loop.
 */
@RestController
@RequestMapping("/api/forecast")
public class DailyForecastPredictionController {

    private final DailyForecastPredictionService dailyForecastPredictionService;
    private final DailyTrajectoryService dailyTrajectoryService;

    public DailyForecastPredictionController(DailyForecastPredictionService dailyForecastPredictionService,
                                              DailyTrajectoryService dailyTrajectoryService) {
        this.dailyForecastPredictionService = dailyForecastPredictionService;
        this.dailyTrajectoryService = dailyTrajectoryService;
    }

    @PostMapping("/{instrument}/daily/predict-today")
    public HourlyPrediction predictToday(@PathVariable Instrument instrument) {
        return dailyForecastPredictionService.recordTodayPrediction(instrument);
    }

    @PostMapping("/{instrument}/daily/evaluate")
    public Map<String, Integer> evaluate(@PathVariable Instrument instrument) {
        return Map.of("evaluated", dailyForecastPredictionService.evaluatePending(instrument));
    }

    /** How the actual price moved toward/away from the morning's prediction, hour by hour. Defaults to today (IST). */
    @GetMapping("/{instrument}/daily/trajectory")
    public DailyTrajectory trajectory(@PathVariable Instrument instrument,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return dailyTrajectoryService.getTrajectory(instrument, date);
    }
}
