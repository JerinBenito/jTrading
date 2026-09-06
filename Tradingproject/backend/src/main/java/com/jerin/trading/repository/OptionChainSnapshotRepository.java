package com.jerin.trading.repository;

import com.jerin.trading.domain.OptionChainSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface OptionChainSnapshotRepository extends JpaRepository<OptionChainSnapshot, Long> {

    List<OptionChainSnapshot> findByInstrumentAndExpiryAndTsBetweenOrderByTsAsc(
            String instrument, LocalDate expiry, OffsetDateTime from, OffsetDateTime to);

    @Query("select max(o.ts) from OptionChainSnapshot o where o.instrument = :instrument")
    Optional<OffsetDateTime> findLatestTs(@Param("instrument") String instrument);

    List<OptionChainSnapshot> findByInstrumentAndTs(String instrument, OffsetDateTime ts);

    /** Every snapshot row ever taken for this instrument, regardless of expiry — for building a
     * PCR-over-time series (grouping by ts) rather than a single point-in-time read. */
    List<OptionChainSnapshot> findByInstrumentOrderByTsAsc(String instrument);
}
