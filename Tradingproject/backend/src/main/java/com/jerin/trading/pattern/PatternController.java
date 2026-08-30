package com.jerin.trading.pattern;

import com.jerin.trading.domain.PatternStats;
import com.jerin.trading.ingestion.Instrument;
import com.jerin.trading.repository.PatternStatsRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patterns")
public class PatternController {

    private final PatternStatsService patternStatsService;
    private final PatternStatsRepository patternStatsRepository;
    private final VolumeConfirmationBacktestService volumeConfirmationBacktestService;

    public PatternController(PatternStatsService patternStatsService, PatternStatsRepository patternStatsRepository,
                              VolumeConfirmationBacktestService volumeConfirmationBacktestService) {
        this.patternStatsService = patternStatsService;
        this.patternStatsRepository = patternStatsRepository;
        this.volumeConfirmationBacktestService = volumeConfirmationBacktestService;
    }

    @PostMapping("/{instrument}/recompute")
    public List<PatternStats> recompute(@PathVariable Instrument instrument,
                                         @RequestParam(defaultValue = "1h") String interval) {
        return patternStatsService.recomputeAll(instrument, interval);
    }

    @GetMapping("/stats")
    public List<PatternStats> stats() {
        return patternStatsRepository.findAll();
    }

    /** Read-only — does each pattern's win rate actually differ on high vs. normal volume? Computed fresh from stored history. */
    @GetMapping("/{instrument}/volume-confirmation-backtest")
    public List<VolumeConfirmationResult> volumeConfirmationBacktest(@PathVariable Instrument instrument,
                                                                        @RequestParam(defaultValue = "1h") String interval) {
        return volumeConfirmationBacktestService.compare(instrument, interval);
    }

    /** Same as above, against the underlying's futures contract (real volume) instead of the index (always zero/null volume). */
    @GetMapping("/{instrument}/futures-volume-confirmation-backtest")
    public List<VolumeConfirmationResult> futuresVolumeConfirmationBacktest(@PathVariable Instrument instrument,
                                                                               @RequestParam(defaultValue = "1h") String interval) {
        return volumeConfirmationBacktestService.compare(instrument.name() + "_FUT", interval);
    }
}
