package com.jerin.trading.ml;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface AiPredictionSnapshotRepository extends JpaRepository<AiPredictionSnapshot, Long> {

    /** The earliest call for a given day — the fair, hardest-information test of real skill,
     * made before any later re-prediction had a chance to lean on the day's own price action. */
    @Query("select s from AiPredictionSnapshot s where s.instrument = :instrument and s.horizon = :horizon "
            + "and s.targetDate = :targetDate order by s.predictedAtTs asc limit 1")
    Optional<AiPredictionSnapshot> findFirstOfDay(@Param("instrument") String instrument,
                                                   @Param("horizon") String horizon,
                                                   @Param("targetDate") LocalDate targetDate);

    /** The latest call for a given day — how far the AI's own prediction drifted from its first
     * call by the time re-predictions stopped; the raw material for a "revision gradient". */
    @Query("select s from AiPredictionSnapshot s where s.instrument = :instrument and s.horizon = :horizon "
            + "and s.targetDate = :targetDate order by s.predictedAtTs desc limit 1")
    Optional<AiPredictionSnapshot> findLastOfDay(@Param("instrument") String instrument,
                                                  @Param("horizon") String horizon,
                                                  @Param("targetDate") LocalDate targetDate);
}
