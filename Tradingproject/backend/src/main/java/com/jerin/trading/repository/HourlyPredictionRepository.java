package com.jerin.trading.repository;

import com.jerin.trading.domain.HourlyPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface HourlyPredictionRepository extends JpaRepository<HourlyPrediction, Long> {

    List<HourlyPrediction> findByInstrumentAndIntervalOrderByPredictedForTsDesc(String instrument, String interval);

    List<HourlyPrediction> findByInstrumentAndIntervalAndActualCloseIsNull(String instrument, String interval);

    Optional<HourlyPrediction> findByInstrumentAndIntervalAndPredictedForTs(
            String instrument, String interval, OffsetDateTime predictedForTs);

    /** Most recent N *evaluated* predictions, used to compute the rolling bias correction. */
    @Query("select h from HourlyPrediction h where h.instrument = :instrument and h.interval = :interval "
            + "and h.actualClose is not null order by h.predictedForTs desc")
    List<HourlyPrediction> findRecentEvaluated(@Param("instrument") String instrument,
                                                @Param("interval") String interval);
}
