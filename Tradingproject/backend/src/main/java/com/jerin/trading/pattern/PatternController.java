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

    public PatternController(PatternStatsService patternStatsService, PatternStatsRepository patternStatsRepository) {
        this.patternStatsService = patternStatsService;
        this.patternStatsRepository = patternStatsRepository;
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
}
