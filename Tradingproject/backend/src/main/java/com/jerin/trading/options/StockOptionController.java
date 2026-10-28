package com.jerin.trading.options;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Recorded option-chain summaries for the basket stocks (see {@link StockOptionSummaryService}). */
@RestController
@RequestMapping("/api/options/stock")
public class StockOptionController {

    private final StockOptionSummaryService service;
    private final StockOptionSummaryRepository repository;

    public StockOptionController(StockOptionSummaryService service, StockOptionSummaryRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    /** Captures and stores one stock right now (also what the hourly job does for all 50). */
    @PostMapping("/{symbol}/capture")
    public ResponseEntity<StockOptionSummary> capture(@PathVariable String symbol) {
        return service.capture(symbol).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/capture-all")
    public Map<String, Object> captureAll() {
        return Map.of("stored", service.captureAll());
    }

    @GetMapping("/{symbol}/history")
    public List<StockOptionSummary> history(@PathVariable String symbol, @RequestParam(defaultValue = "3") int days) {
        return repository.findByInstrumentAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc(symbol, OffsetDateTime.now().minusDays(Math.max(1, Math.min(days, 60))));
    }

    /** Everything captured in the last N days (default 1), for offline research. */
    @GetMapping("/recent")
    public List<StockOptionSummary> recent(@RequestParam(defaultValue = "1") int days) {
        return repository.findByCapturedAtGreaterThanEqualOrderByInstrumentAscCapturedAtAsc(OffsetDateTime.now().minusDays(Math.max(1, Math.min(days, 60))));
    }
}
