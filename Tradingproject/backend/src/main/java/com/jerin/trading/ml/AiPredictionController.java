package com.jerin.trading.ml;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Live AI-prediction ledger — see {@link AiPredictionService}. Predictions are submitted by the
 * offline training/inference scripts in {@code ml-training/} (this backend never runs model
 * inference itself); evaluation against real outcomes happens here, in Java, reusing the same
 * candle data every other prediction service uses.
 */
@RestController
@RequestMapping("/api/ai-predictions")
public class AiPredictionController {

    private final AiPredictionService predictionService;

    public AiPredictionController(AiPredictionService predictionService) {
        this.predictionService = predictionService;
    }

    public record RecordRequest(
            String instrument, String horizon, String valueType, String modelVersion,
            BigDecimal predictedValue, BigDecimal baselineValue,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate,
            /** Only needed for RETURN_PCT (FORWARD_*) predictions — the % converted to a real
             * price using the anchor close the caller computed it from. Ignored for PRICE
             * (INTRADAY), which derives its price directly from predictedValue/baselineValue. */
            BigDecimal predictedPrice, BigDecimal baselinePrice) {
    }

    /** Returns the stored prediction, or {@code {"recorded": false, ...}} (still valid JSON, since
     * the offline script parses every response) when an INTRADAY call arrives after the close on
     * its own target day — see {@link AiPredictionService#isPastRecordingWindow}. */
    @PostMapping
    public Object record(@RequestBody RecordRequest request) {
        if (predictionService.isPastRecordingWindow(request.horizon(), request.targetDate())) {
            return Map.of("recorded", false,
                    "reason", "INTRADAY call arrived after the 15:30 IST close on its target day; its baseline would already equal the actual close");
        }
        return predictionService.recordPrediction(
                request.instrument(), request.horizon(), request.valueType(), request.modelVersion(),
                request.predictedValue(), request.baselineValue(), request.targetDate(),
                request.predictedPrice(), request.baselinePrice());
    }

    /** Evaluates every pending prediction across all instruments/horizons whose outcome is now knowable. */
    @PostMapping("/evaluate-all")
    public Map<String, Integer> evaluateAll() {
        return Map.of("evaluated", predictionService.evaluatePending());
    }

    @GetMapping("/{instrument}")
    public List<AiPrediction> history(@PathVariable String instrument, @RequestParam String horizon) {
        return predictionService.history(instrument, horizon);
    }

    /** The metric that actually matters — accuracy over many recent predictions, never a single one. */
    @GetMapping("/{instrument}/rolling-accuracy")
    public RollingAccuracy rollingAccuracy(@PathVariable String instrument, @RequestParam String horizon,
                                            @RequestParam(defaultValue = "20") int window) {
        return predictionService.rollingAccuracy(instrument, horizon, window);
    }

    /** The fair version of {@link #rollingAccuracy} for a re-predicted horizon like INTRADAY —
     * scores the day's FIRST call instead of whichever call was latest when evaluated. See
     * {@link FirstCallRollingAccuracy}. */
    @GetMapping("/{instrument}/rolling-accuracy-first-call")
    public FirstCallRollingAccuracy firstCallRollingAccuracy(@PathVariable String instrument, @RequestParam String horizon,
                                                               @RequestParam(defaultValue = "20") int window) {
        return predictionService.firstCallRollingAccuracy(instrument, horizon, window);
    }
}
