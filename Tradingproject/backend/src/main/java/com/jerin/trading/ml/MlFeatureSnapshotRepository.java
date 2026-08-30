package com.jerin.trading.ml;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MlFeatureSnapshotRepository extends JpaRepository<MlFeatureSnapshot, Long> {

    Optional<MlFeatureSnapshot> findByInstrumentAndTradingDate(String instrument, LocalDate tradingDate);

    List<MlFeatureSnapshot> findByInstrumentOrderByTradingDateAsc(String instrument);
}
