package com.jerin.trading.ingestion;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Manual/on-demand backfill for the NIFTY 50 stock basket — see {@link EquityBasketIngestionService}. */
@RestController
@RequestMapping("/api/ingestion/equity-basket")
public class EquityBasketIngestionController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final EquityBasketIngestionService equityBasketIngestionService;

    public EquityBasketIngestionController(EquityBasketIngestionService equityBasketIngestionService) {
        this.equityBasketIngestionService = equityBasketIngestionService;
    }

    /** Backfill one symbol — use this to verify a trading symbol resolves before running the full basket. */
    @PostMapping("/{symbol}/backfill")
    public EquityIngestionResult backfillOne(@PathVariable String symbol,
                                              @RequestParam(defaultValue = "hours") String unit,
                                              @RequestParam(defaultValue = "1") int interval,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(IST);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusYears(2);
        return equityBasketIngestionService.backfillSymbol(symbol.toUpperCase(), unit, interval, effectiveFrom, effectiveTo);
    }

    /** Backfill all 50 basket symbols — expensive (50x the single-symbol call count), run only after verifying one symbol works. */
    @PostMapping("/backfill")
    public Map<String, Object> backfillAll(@RequestParam(defaultValue = "hours") String unit,
                                            @RequestParam(defaultValue = "1") int interval,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(IST);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusYears(2);

        List<EquityIngestionResult> results = equityBasketIngestionService.backfillBasket(unit, interval, effectiveFrom, effectiveTo);
        long found = results.stream().filter(r -> "OK".equals(r.status())).count();
        int totalCandles = results.stream().mapToInt(EquityIngestionResult::candlesSaved).sum();

        return Map.of(
                "from", effectiveFrom, "to", effectiveTo,
                "symbolsRequested", results.size(),
                "symbolsResolved", found,
                "totalCandlesSaved", totalCandles,
                "results", results);
    }
}
