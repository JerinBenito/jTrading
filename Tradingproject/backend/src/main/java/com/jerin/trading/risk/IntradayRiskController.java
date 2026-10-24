package com.jerin.trading.risk;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Same-day remaining-swing forecasts ("how far can it still move before the close"), pushed by the hourly AI run and read by
 * the app's stock page. Stored per instrument/date/hour; a later push for the same hour replaces the earlier one.
 */
@RestController
@RequestMapping("/api/intraday-risk")
public class IntradayRiskController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record PushItem(String instrument, LocalDate targetDate, int hoursSinceOpen, BigDecimal currentPrice,
                           BigDecimal expectedSwingPct, BigDecimal upper90SwingPct, String modelVersion) {}

    private final IntradayRiskForecastRepository repository;

    public IntradayRiskController(IntradayRiskForecastRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> push(@RequestBody List<PushItem> items) {
        OffsetDateTime now = OffsetDateTime.now();
        for (PushItem it : items) {
            IntradayRiskForecast row = repository
                    .findByInstrumentAndTargetDateAndHoursSinceOpen(it.instrument(), it.targetDate(), it.hoursSinceOpen())
                    .orElseGet(() -> IntradayRiskForecast.builder().instrument(it.instrument()).targetDate(it.targetDate())
                            .hoursSinceOpen(it.hoursSinceOpen()).build());
            row.setPredictedAt(now);
            row.setCurrentPrice(it.currentPrice());
            row.setExpectedSwingPct(it.expectedSwingPct());
            row.setUpper90SwingPct(it.upper90SwingPct());
            row.setModelVersion(it.modelVersion());
            repository.save(row);
        }
        return Map.of("saved", items.size());
    }

    /** The newest forecast of the day for every instrument (default: today, IST). */
    @GetMapping("/latest")
    public List<IntradayRiskForecast> latest(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now(IST);
        return repository.findByTargetDateOrderByInstrumentAscHoursSinceOpenAsc(d).stream()
                .collect(Collectors.toMap(IntradayRiskForecast::getInstrument, f -> f, (a, b) -> a.getHoursSinceOpen() >= b.getHoursSinceOpen() ? a : b))
                .values().stream().sorted(Comparator.comparing(IntradayRiskForecast::getInstrument)).toList();
    }

    @GetMapping("/{instrument}")
    public List<IntradayRiskForecast> forInstrument(@PathVariable String instrument,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return repository.findByInstrumentAndTargetDateOrderByHoursSinceOpenAsc(instrument, date != null ? date : LocalDate.now(IST));
    }
}
