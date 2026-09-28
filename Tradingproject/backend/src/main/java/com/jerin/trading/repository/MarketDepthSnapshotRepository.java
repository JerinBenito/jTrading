package com.jerin.trading.repository;

import com.jerin.trading.domain.MarketDepthSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketDepthSnapshotRepository extends JpaRepository<MarketDepthSnapshot, Long> {
}
