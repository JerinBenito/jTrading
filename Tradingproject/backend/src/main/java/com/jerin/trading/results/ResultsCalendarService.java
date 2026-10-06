package com.jerin.trading.results;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quarterly results calendar. The daily AI run pushes each stock's full date list (Yahoo, via
 * yfinance); this stores it and serves the "results today / soon" flag. Future dates are a vendor's
 * announced-or-estimated dates and can move, so each push REPLACES that stock's future rows rather
 * than only adding to them - a date that disappears or shifts must not linger as a stale flag.
 */
@Service
public class ResultsCalendarService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record DateInput(LocalDate resultsDate, BigDecimal epsEstimate) {}

    public record Entry(String instrument, LocalDate resultsDate, BigDecimal epsEstimate, long daysUntil) {}

    private final ResultsCalendarRepository repository;

    public ResultsCalendarService(ResultsCalendarRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int replaceForInstrument(String instrument, List<DateInput> dates) {
        LocalDate today = LocalDate.now(IST);
        Set<LocalDate> incoming = new HashSet<>();
        for (DateInput d : dates) {
            incoming.add(d.resultsDate());
        }
        // Future rows the vendor no longer lists (a moved date) are dropped; past rows are history.
        for (ResultsCalendarEntry existing : repository.findByInstrumentAndResultsDateGreaterThanEqual(instrument, today.minusDays(1))) {
            if (!incoming.contains(existing.getResultsDate())) {
                repository.delete(existing);
            }
        }
        Map<LocalDate, ResultsCalendarEntry> byDate = new HashMap<>();
        for (ResultsCalendarEntry e : repository.findByInstrumentOrderByResultsDateAsc(instrument)) {
            byDate.put(e.getResultsDate(), e);
        }
        OffsetDateTime now = OffsetDateTime.now();
        int written = 0;
        for (DateInput d : dates) {
            ResultsCalendarEntry row = byDate.get(d.resultsDate());
            if (row == null) {
                row = ResultsCalendarEntry.builder().instrument(instrument).resultsDate(d.resultsDate()).build();
            }
            row.setEpsEstimate(d.epsEstimate());
            row.setFetchedAt(now);
            repository.save(row);
            written++;
        }
        return written;
    }

    /** Results from yesterday (the reaction day when announced after the close) through {@code daysAhead} days out. */
    public List<Entry> upcoming(int daysAhead) {
        LocalDate today = LocalDate.now(IST);
        return repository.findByResultsDateBetweenOrderByResultsDateAscInstrumentAsc(today.minusDays(1), today.plusDays(daysAhead))
                .stream().map(e -> toEntry(e, today)).toList();
    }

    public List<Entry> forInstrument(String instrument) {
        LocalDate today = LocalDate.now(IST);
        return repository.findByInstrumentOrderByResultsDateAsc(instrument).stream().map(e -> toEntry(e, today)).toList();
    }

    private static Entry toEntry(ResultsCalendarEntry e, LocalDate today) {
        return new Entry(e.getInstrument(), e.getResultsDate(), e.getEpsEstimate(),
                ChronoUnit.DAYS.between(today, e.getResultsDate()));
    }
}
