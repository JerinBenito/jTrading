package com.jerin.trading.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "pattern_stats")
@IdClass(PatternStatsId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatternStats {

    @Id
    @Column(name = "pattern_id", length = 50)
    private String patternId;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Id
    @Column(name = "window_end", nullable = false)
    private LocalDate windowEnd;

    @Column(name = "sample_size", nullable = false)
    private Integer sampleSize;

    @Column(name = "win_rate", precision = 5, scale = 2)
    private BigDecimal winRate;

    @Column(name = "avg_move_pct", precision = 6, scale = 3)
    private BigDecimal avgMovePct;

    @Column(name = "last_recalibrated", nullable = false)
    private OffsetDateTime lastRecalibrated;
}
