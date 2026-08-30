package com.jerin.trading.ingestion;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/** Manual/on-demand futures backfill — separate from the index ingestion in {@link IngestionController}. */
@RestController
@RequestMapping("/api/ingestion/futures")
public class FuturesIngestionController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final FuturesIngestionService futuresIngestionService;

    public FuturesIngestionController(FuturesIngestionService futuresIngestionService) {
        this.futuresIngestionService = futuresIngestionService;
    }

    /** {@code instrument} is NIFTY or BANKNIFTY — the underlying, not the futures contract itself; stored under e.g. "NIFTY_FUT". */
    @PostMapping("/{instrument}/backfill")
    public Map<String, Object> backfill(@PathVariable Instrument instrument,
                                         @RequestParam(defaultValue = "hours") String unit,
                                         @RequestParam(defaultValue = "1") int interval,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(IST);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(3);
        String instrumentTag = instrument.name() + "_FUT";

        Optional<FuturesIngestionResult> result = futuresIngestionService.backfillNearMonth(
                instrument.name(), instrumentTag, unit, interval, effectiveFrom, effectiveTo);

        if (result.isEmpty()) {
            return Map.of("status", "no active near-month futures contract found for " + instrument.name());
        }
        return Map.of("status", "ok", "result", result.get());
    }
}
