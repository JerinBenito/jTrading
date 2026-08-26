package com.jerin.trading.repository;

import com.jerin.trading.domain.PatternStats;
import com.jerin.trading.domain.PatternStatsId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PatternStatsRepository extends JpaRepository<PatternStats, PatternStatsId> {

    List<PatternStats> findByPatternIdOrderByWindowEndDesc(String patternId);
}
