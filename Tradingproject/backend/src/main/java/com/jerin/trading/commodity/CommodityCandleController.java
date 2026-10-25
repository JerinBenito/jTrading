package com.jerin.trading.commodity;

import com.jerin.trading.broker.BrokerClient;
import com.jerin.trading.broker.Candle;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Read-only pass-through of MCX candles (daily or intraday) for the commodity report job, which runs on GitHub's
 * runners and has no broker token of its own. Restricted to MCX_FO instrument keys (futures and options) so it cannot be
 * used to spend the broker rate limit on anything else, and responses are cached for a few minutes. Compact rows
 * [epochSeconds, open, high, low, close, volume], in whatever order the broker returns them.
 */
@RestController
@RequestMapping("/api/commodities")
public class CommodityCandleController {

    private static final Pattern MCX_KEY = Pattern.compile("^MCX_FO\\|\\d{3,9}$");
    private static final Set<String> UNITS = Set.of("days", "minutes", "hours");
    private static final long TTL_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_CACHE = 600;

    private record Cached(long at, List<List<Number>> rows) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final BrokerClient brokerClient;

    public CommodityCandleController(BrokerClient brokerClient) {
        this.brokerClient = brokerClient;
    }

    @GetMapping("/candles")
    public List<List<Number>> candles(@RequestParam String instrumentKey,
                                      @RequestParam(defaultValue = "days") String unit,
                                      @RequestParam(defaultValue = "1") int interval,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!MCX_KEY.matcher(instrumentKey).matches() || !UNITS.contains(unit) || interval < 1 || interval > 60) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only MCX_FO contract keys are served");
        }
        if (from.isAfter(to) || from.isBefore(to.minusDays(800))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date range must be at most 800 days");
        }
        String cacheKey = instrumentKey + "|" + unit + "|" + interval + "|" + from + "|" + to;
        Cached hit = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.at() < TTL_MILLIS) {
            return hit.rows();
        }
        List<List<Number>> rows = new ArrayList<>();
        for (Candle c : brokerClient.getCandles(instrumentKey, unit, interval, from, to)) {
            rows.add(List.of(c.ts().toEpochSecond(), c.open().doubleValue(), c.high().doubleValue(), c.low().doubleValue(),
                    c.close().doubleValue(), c.volume() == null ? 0L : c.volume()));
        }
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(cacheKey, new Cached(now, rows));
        return rows;
    }
}
