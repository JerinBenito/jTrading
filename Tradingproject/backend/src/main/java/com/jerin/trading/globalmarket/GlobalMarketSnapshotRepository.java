package com.jerin.trading.globalmarket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GlobalMarketSnapshotRepository extends JpaRepository<GlobalMarketSnapshot, Long> {

    Optional<GlobalMarketSnapshot> findBySymbolAndTradingDate(String symbol, LocalDate tradingDate);

    List<GlobalMarketSnapshot> findByTradingDateOrderBySymbolAsc(LocalDate tradingDate);

    /** Most recent snapshot for one symbol, whatever day it's from — for "latest known read" display. */
    @Query("select s from GlobalMarketSnapshot s where s.symbol = :symbol order by s.tradingDate desc")
    List<GlobalMarketSnapshot> findRecentBySymbol(@Param("symbol") String symbol);
}
