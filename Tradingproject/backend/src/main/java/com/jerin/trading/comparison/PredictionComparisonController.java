package com.jerin.trading.comparison;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Every model's prediction for one instrument+day, normalized into one list — see {@link PredictionComparisonService}. */
@RestController
@RequestMapping("/api/predictions-comparison")
public class PredictionComparisonController {

    private final PredictionComparisonService comparisonService;

    public PredictionComparisonController(PredictionComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    /** @param date defaults to today (IST) when omitted */
    @GetMapping("/{instrument}")
    public PredictionComparisonResponse compare(@PathVariable String instrument,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return comparisonService.compare(instrument, date);
    }

    @GetMapping("/{instrument}/leaderboard")
    public ModelLeaderboard leaderboard(@PathVariable String instrument) {
        return comparisonService.leaderboard(instrument);
    }
}
