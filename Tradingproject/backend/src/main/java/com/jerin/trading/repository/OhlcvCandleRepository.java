package com.jerin.trading.repository;

import com.jerin.trading.domain.OhlcvCandle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OhlcvCandleRepository extends JpaRepository<OhlcvCandle, Long> {

    List<OhlcvCandle> findByInstrumentAndIntervalAndTsBetweenOrderByTsAsc(
            String instrument, String interval, OffsetDateTime from, OffsetDateTime to);

    boolean existsByInstrumentAndIntervalAndTs(String instrument, String interval, OffsetDateTime ts);

    Optional<OhlcvCandle> findByInstrumentAndIntervalAndTs(String instrument, String interval, OffsetDateTime ts);

    List<OhlcvCandle> findTop200ByInstrumentAndIntervalOrderByTsDesc(String instrument, String interval);

    List<OhlcvCandle> findByInstrumentAndIntervalOrderByTsAsc(String instrument, String interval);
}
