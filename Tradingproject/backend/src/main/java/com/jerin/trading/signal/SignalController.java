package com.jerin.trading.signal;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** {@code instrument} accepts NIFTY/BANKNIFTY as before, or any NIFTY 50 basket trading symbol —
 * signal_predictions/signal_outcomes are genuinely keyed by instrument, and pattern_stats is now
 * too (see the 2026-09-06 schema fix), so nothing here is NIFTY/BANKNIFTY-only anymore. */
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
    public List<SignalResponse> generate(@PathVariable String instrument,
                                          @RequestParam(defaultValue = "1h") String interval) {
        return signalService.generateSignals(instrument, interval);
    }

    @PostMapping("/{instrument}/evaluate-outcomes")
    public Map<String, Integer> evaluateOutcomes(@PathVariable String instrument,
                                                  @RequestParam(defaultValue = "1h") String interval) {
        return Map.of("evaluated", outcomeEvaluationService.evaluatePending(instrument, interval));
    }

    /** Read-only — what actually happened, no live check triggered. This is what "checking in" should hit. */
    @GetMapping("/{instrument}/history")
    public List<SignalHistoryEntry> history(@PathVariable String instrument) {
        return signalService.getHistory(instrument);
    }
}
