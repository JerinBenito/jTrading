package com.jerin.trading.repository;

import com.jerin.trading.domain.SignalOutcome;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SignalOutcomeRepository extends JpaRepository<SignalOutcome, Long> {

    Optional<SignalOutcome> findByPredictionId(Long predictionId);
}
