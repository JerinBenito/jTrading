package com.jerin.trading.repository;

import com.jerin.trading.domain.SignalPrediction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface SignalPredictionRepository extends JpaRepository<SignalPrediction, Long> {

    List<SignalPrediction> findByInstrumentOrderByTsDesc(String instrument);

    boolean existsByInstrumentAndPatternIdAndTs(String instrument, String patternId, OffsetDateTime ts);
}
