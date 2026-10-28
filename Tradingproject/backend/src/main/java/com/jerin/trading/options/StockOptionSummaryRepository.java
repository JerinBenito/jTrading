package com.jerin.trading.options;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface StockOptionSummaryRepository extends JpaRepository<StockOptionSummary, Long> {

    List<StockOptionSummary> findByInstrumentAndCapturedAtGreaterThanEqualOrderByCapturedAtAsc(String instrument, OffsetDateTime from);

    List<StockOptionSummary> findByCapturedAtGreaterThanEqualOrderByInstrumentAscCapturedAtAsc(OffsetDateTime from);
}
