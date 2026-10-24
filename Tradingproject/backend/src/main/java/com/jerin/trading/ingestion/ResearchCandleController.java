package com.jerin.trading.ingestion;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.Candle;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only pass-through of broker candles for OFFLINE research (e.g. 1-minute history back to 2022, ~23 million rows
 * for the basket - far too big to store on this 1 GB server, so it is streamed to the researcher's own machine and
 * nothing is saved here). Disabled unless the RESEARCH_KEY environment variable is set, and every call must send
 * the same value in the X-Research-Key header, because the endpoint spends the Upstox rate limit and the rest of
 * this API has no authentication. Added 2026-10-24; remove or unset the key when the download is finished.
 *
 * Returns compact rows [epochSeconds, open, high, low, close, volume].
 */
@RestController
@RequestMapping("/api/research")
public class ResearchCandleController {

    private final BrokerClient brokerClient;
    private final EquityBasketIngestionService equityBasketIngestionService;

    public ResearchCandleController(BrokerClient brokerClient, EquityBasketIngestionService equityBasketIngestionService) {
        this.brokerClient = brokerClient;
        this.equityBasketIngestionService = equityBasketIngestionService;
    }

    @GetMapping("/candles/{symbol}")
    public List<List<Number>> candles(@PathVariable String symbol,
                                      @RequestParam(defaultValue = "minutes") String unit,
                                      @RequestParam(defaultValue = "1") int interval,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestHeader(value = "X-Research-Key", required = false) String key) {
        String expected = System.getenv("RESEARCH_KEY");
        if (expected == null || expected.isBlank() || !expected.equals(key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);              // looks like it does not exist
        }
        String instrumentKey;
        if ("NIFTY".equals(symbol) || "BANKNIFTY".equals(symbol)) {
            instrumentKey = Instrument.valueOf(symbol).brokerKey();
        } else {
            Optional<String> resolved = equityBasketIngestionService.findInstrumentKey(symbol);
            instrumentKey = resolved.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown symbol"));
        }
        List<List<Number>> rows = new ArrayList<>();
        for (Candle c : brokerClient.getCandles(instrumentKey, unit, interval, from, to)) {
            rows.add(List.of(c.ts().toEpochSecond(), c.open().doubleValue(), c.high().doubleValue(), c.low().doubleValue(),
                    c.close().doubleValue(), c.volume() == null ? 0L : c.volume()));
        }
        return rows;
    }
}
