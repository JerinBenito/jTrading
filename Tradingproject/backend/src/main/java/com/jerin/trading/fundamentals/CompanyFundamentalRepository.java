package com.jerin.trading.fundamentals;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyFundamentalRepository extends JpaRepository<CompanyFundamental, Long> {

    Optional<CompanyFundamental> findBySymbolAndRatioName(String symbol, String ratioName);

    List<CompanyFundamental> findBySymbolOrderByRatioNameAsc(String symbol);
}
