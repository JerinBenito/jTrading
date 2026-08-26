package com.jerin.trading.indicator;

import com.jerin.trading.ingestion.Instrument;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/indicators")
public class IndicatorController {

    private final IndicatorService indicatorService;

    public IndicatorController(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @GetMapping("/{instrument}")
    public IndicatorSnapshot latest(@PathVariable Instrument instrument,
                                     @RequestParam(defaultValue = "1h") String interval) {
        return indicatorService.latest(instrument, interval);
    }
}
