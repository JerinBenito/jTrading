package com.jerin.trading.commodity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CommodityReportRepository extends JpaRepository<CommodityReport, Long> {

    Optional<CommodityReport> findByReportDate(LocalDate reportDate);

    List<CommodityReport> findAllByOrderByReportDateDesc(Pageable pageable);
}
