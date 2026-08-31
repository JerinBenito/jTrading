package com.jerin.trading.ml;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiPredictionRepository extends JpaRepository<AiPrediction, Long> {

    Optional<AiPrediction> findByInstrumentAndHorizonAndTargetDate(String instrument, String horizon, LocalDate targetDate);

    List<AiPrediction> findByActualValueIsNull();

    List<AiPrediction> findByInstrumentAndHorizonOrderByTargetDateDesc(String instrument, String horizon);

    /** Most recent N *evaluated* predictions for one instrument+horizon — the raw material for rolling accuracy. */
    @Query("select p from AiPrediction p where p.instrument = :instrument and p.horizon = :horizon "
            + "and p.actualValue is not null order by p.targetDate desc")
    List<AiPrediction> findRecentEvaluated(@Param("instrument") String instrument, @Param("horizon") String horizon);
}
