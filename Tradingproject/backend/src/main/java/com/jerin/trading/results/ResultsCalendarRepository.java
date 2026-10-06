package com.jerin.trading.results;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ResultsCalendarRepository extends JpaRepository<ResultsCalendarEntry, Long> {

    List<ResultsCalendarEntry> findByInstrumentOrderByResultsDateAsc(String instrument);

    List<ResultsCalendarEntry> findByResultsDateBetweenOrderByResultsDateAscInstrumentAsc(LocalDate from, LocalDate to);

    List<ResultsCalendarEntry> findByInstrumentAndResultsDateGreaterThanEqual(String instrument, LocalDate from);
}
