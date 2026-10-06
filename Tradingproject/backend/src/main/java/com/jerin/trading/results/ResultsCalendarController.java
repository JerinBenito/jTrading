package com.jerin.trading.results;

import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Quarterly-results calendar for the basket - see {@link ResultsCalendarService}. */
@RestController
@RequestMapping("/api/results-calendar")
public class ResultsCalendarController {

    public record PushRequest(String instrument, List<DateItem> dates) {}

    public record DateItem(LocalDate resultsDate, BigDecimal epsEstimate) {}

    private final ResultsCalendarService service;

    public ResultsCalendarController(ResultsCalendarService service) {
        this.service = service;
    }

    /** Pushed by ml-training/run_daily_ai_prediction.py; replaces each instrument's future dates. */
    @PostMapping
    public Map<String, Object> push(@RequestBody List<PushRequest> requests) {
        int written = 0;
        for (PushRequest r : requests) {
            written += service.replaceForInstrument(r.instrument(),
                    r.dates().stream().map(d -> new ResultsCalendarService.DateInput(d.resultsDate(), d.epsEstimate())).toList());
        }
        return Map.of("instruments", requests.size(), "datesWritten", written);
    }

    @GetMapping("/upcoming")
    public List<ResultsCalendarService.Entry> upcoming(@RequestParam(defaultValue = "14") int days) {
        return service.upcoming(Math.max(0, Math.min(days, 90)));
    }

    @GetMapping("/{instrument}")
    public List<ResultsCalendarService.Entry> forInstrument(@PathVariable String instrument) {
        return service.forInstrument(instrument);
    }
}
