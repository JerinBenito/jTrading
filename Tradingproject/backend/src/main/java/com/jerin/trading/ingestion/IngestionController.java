package com.jerin.trading.ingestion;

import com.jerin.trading.broker.upstox.UpstoxBrokerClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/** Manual/on-demand ingestion — covers the initial historical backfill and lets ingestion
 * be tested outside market hours (the scheduled job normally handles the live cycle itself). */
@RestController
@RequestMapping("/api/ingestion")
public class IngestionController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Upstox limits "hours" unit requests to about one quarter per call (UDAPI1148 otherwise). */
    private static final int MAX_HOURLY_WINDOW_DAYS = 89;

    private final IngestionService ingestionService;
    private final UpstoxBrokerClient upstoxBrokerClient;

    public IngestionController(IngestionService ingestionService, UpstoxBrokerClient upstoxBrokerClient) {
        this.ingestionService = ingestionService;
        this.upstoxBrokerClient = upstoxBrokerClient;
    }

    @PostMapping("/{instrument}/backfill")
    public Map<String, Object> backfill(@PathVariable Instrument instrument,
                                         @RequestParam(defaultValue = "hours") String unit,
                                         @RequestParam(defaultValue = "1") int interval,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now(IST);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(3);

        int windowDays = "hours".equals(unit) ? MAX_HOURLY_WINDOW_DAYS : Integer.MAX_VALUE;
        int totalSaved = 0;
        LocalDate chunkFrom = effectiveFrom;
        while (!chunkFrom.isAfter(effectiveTo)) {
            LocalDate chunkTo = chunkFrom.plusDays(windowDays - 1);
            if (chunkTo.isAfter(effectiveTo)) {
                chunkTo = effectiveTo;
            }
            totalSaved += ingestionService.ingestCandles(instrument, unit, interval, chunkFrom, chunkTo);
            chunkFrom = chunkTo.plusDays(1);
        }
        return Map.of("instrument", instrument.name(), "from", effectiveFrom, "to", effectiveTo, "candlesSaved", totalSaved);
    }

    /** Today's live/forming candles — same call the scheduled job makes each hour; exposed here for manual testing. */
    @PostMapping("/{instrument}/intraday")
    public Map<String, Object> intraday(@PathVariable Instrument instrument,
                                         @RequestParam(defaultValue = "hours") String unit,
                                         @RequestParam(defaultValue = "1") int interval) {
        int saved = ingestionService.ingestIntradayCandles(instrument, unit, interval);
        return Map.of("instrument", instrument.name(), "candlesSaved", saved);
    }

    @PostMapping("/{instrument}/option-chain")
    public Map<String, Object> optionChain(@PathVariable Instrument instrument,
                                            @RequestParam(defaultValue = "current_week") String expiry) {
        int saved = ingestionService.ingestOptionChain(instrument, expiry);
        return Map.of("instrument", instrument.name(), "expiry", expiry, "rowsSaved", saved);
    }

    /** Temporary verification-only endpoint — see {@link com.jerin.trading.broker.upstox.UpstoxBrokerClient#getKeyRatiosRaw}. */
    @GetMapping("/fundamentals-spike/{isin}")
    public String fundamentalsSpike(@PathVariable String isin) {
        return upstoxBrokerClient.getKeyRatiosRaw(isin);
    }
}
