package com.jerin.trading.forecast;

import com.jerin.trading.domain.HourlyPrediction;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.HourlyPredictionRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/forecast")
public class ForecastPredictionController {

    private final ForecastPredictionService forecastPredictionService;
    private final HourlyPredictionRepository predictionRepository;

    public ForecastPredictionController(ForecastPredictionService forecastPredictionService,
                                         HourlyPredictionRepository predictionRepository) {
        this.forecastPredictionService = forecastPredictionService;
        this.predictionRepository = predictionRepository;
    }

    /** Read-only — every prediction made, and its actual outcome once resolved. This is the URI to check in on. */
    @GetMapping("/{instrument}/history")
    public List<HourlyPrediction> history(@PathVariable Instrument instrument,
                                           @RequestParam(defaultValue = "1h") String interval) {
        return predictionRepository.findByInstrumentAndIntervalOrderByPredictedForTsDesc(instrument.name(), interval);
    }

    /** Manual triggers — the scheduled job does this automatically every hour; these are for testing on demand. */
    @PostMapping("/{instrument}/predict-next")
    public HourlyPrediction predictNext(@PathVariable Instrument instrument,
                                         @RequestParam(defaultValue = "1h") String interval) {
        return forecastPredictionService.recordNextPrediction(instrument, interval);
    }

    @PostMapping("/{instrument}/evaluate")
    public Map<String, Integer> evaluate(@PathVariable Instrument instrument,
                                          @RequestParam(defaultValue = "1h") String interval) {
        return Map.of("evaluated", forecastPredictionService.evaluatePending(instrument, interval));
    }
}
