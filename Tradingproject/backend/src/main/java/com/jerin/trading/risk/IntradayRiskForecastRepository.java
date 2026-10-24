package com.jerin.trading.risk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface IntradayRiskForecastRepository extends JpaRepository<IntradayRiskForecast, Long> {

    Optional<IntradayRiskForecast> findByInstrumentAndTargetDateAndHoursSinceOpen(String instrument, LocalDate targetDate, int hoursSinceOpen);

    List<IntradayRiskForecast> findByTargetDateOrderByInstrumentAscHoursSinceOpenAsc(LocalDate targetDate);

    List<IntradayRiskForecast> findByInstrumentAndTargetDateOrderByHoursSinceOpenAsc(String instrument, LocalDate targetDate);
}
