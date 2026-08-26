package com.jerin.trading.signal;

import com.jerin.trading.ingestion.Instrument;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/signals")
public class SignalController {

    private final SignalService signalService;
    private final OutcomeEvaluationService outcomeEvaluationService;

    public SignalController(SignalService signalService, OutcomeEvaluationService outcomeEvaluationService) {
        this.signalService = signalService;
        this.outcomeEvaluationService = outcomeEvaluationService;
    }

    @GetMapping("/{instrument}")
    public List<SignalResponse> generate(@PathVariable Instrument instrument,
                                          @RequestParam(defaultValue = "1h") String interval) {
        return signalService.generateSignals(instrument, interval);
    }

    @PostMapping("/{instrument}/evaluate-outcomes")
    public Map<String, Integer> evaluateOutcomes(@PathVariable Instrument instrument,
                                                  @RequestParam(defaultValue = "1h") String interval) {
        return Map.of("evaluated", outcomeEvaluationService.evaluatePending(instrument, interval));
    }

    /** Read-only — what actually happened, no live check triggered. This is what "checking in" should hit. */
    @GetMapping("/{instrument}/history")
    public List<SignalHistoryEntry> history(@PathVariable Instrument instrument) {
        return signalService.getHistory(instrument);
    }
}
