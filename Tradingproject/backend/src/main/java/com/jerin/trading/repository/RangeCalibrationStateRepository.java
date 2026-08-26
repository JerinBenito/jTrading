package com.jerin.trading.repository;

import com.jerin.trading.domain.RangeCalibrationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RangeCalibrationStateRepository extends JpaRepository<RangeCalibrationState, Long> {

    Optional<RangeCalibrationState> findByInstrumentAndInterval(String instrument, String interval);
}
