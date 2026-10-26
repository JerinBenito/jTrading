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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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
        List<Candle> candles = new ArrayList<>(brokerClient.getCandles(instrumentKey, unit, interval, from, to));
        if (!to.isBefore(LocalDate.now(IST))) {
            // the broker's historical endpoint never includes the current day: add it from the intraday endpoint
            HashSet<Long> have = new HashSet<>();
            candles.forEach(c -> have.add(c.ts().toEpochSecond()));
            for (Candle c : todayCandles(instrumentKey, unit, interval)) {
                if (have.add(c.ts().toEpochSecond())) {
                    candles.add(c);
                }
            }
        }
        List<List<Number>> rows = new ArrayList<>();
        for (Candle c : candles) {
            rows.add(List.of(c.ts().toEpochSecond(), c.open().doubleValue(), c.high().doubleValue(), c.low().doubleValue(),
                    c.close().doubleValue(), c.volume() == null ? 0L : c.volume()));
        }
        if (cache.size() >= MAX_CACHE) {
            cache.clear();
        }
        cache.put(cacheKey, new Cached(now, rows));
        return rows;
    }

    /** Today's candles (empty outside a session). A daily bar, if the broker has no daily intraday unit, is built from 1-minute candles. */
    private List<Candle> todayCandles(String key, String unit, int interval) {
        try {
            List<Candle> c = brokerClient.getIntradayCandles(key, unit, interval);
            if (!c.isEmpty()) {
                return c;
            }
        } catch (RuntimeException ignored) {
            // fall through to the minute-built bar for "days"
        }
        if (!"days".equals(unit)) {
            return List.of();
        }
        try {
            List<Candle> minutes = new ArrayList<>(brokerClient.getIntradayCandles(key, "minutes", 1));
            if (minutes.isEmpty()) {
                return List.of();
            }
            minutes.sort(Comparator.comparing(Candle::ts));
            BigDecimal high = minutes.get(0).high();
            BigDecimal low = minutes.get(0).low();
            long volume = 0;
            for (Candle m : minutes) {
                high = high.max(m.high());
                low = low.min(m.low());
                volume += m.volume() == null ? 0 : m.volume();
            }
            Candle first = minutes.get(0);
            Candle last = minutes.get(minutes.size() - 1);
            OffsetDateTime dayStart = first.ts().atZoneSameInstant(IST).toLocalDate().atStartOfDay(IST).toOffsetDateTime();
            return List.of(new Candle(dayStart, first.open(), high, low, last.close(), volume, last.openInterest()));
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
