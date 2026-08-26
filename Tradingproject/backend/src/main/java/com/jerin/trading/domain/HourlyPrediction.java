package com.jerin.trading.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "hourly_predictions", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "interval", "predicted_for_ts"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HourlyPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(name = "model_name", nullable = false, length = 50)
    private String modelName;

    @Column(name = "predicted_at_ts", nullable = false)
    private OffsetDateTime predictedAtTs;

    @Column(name = "predicted_for_ts", nullable = false)
    private OffsetDateTime predictedForTs;

    @Column(name = "predicted_close", nullable = false, precision = 12, scale = 2)
    private BigDecimal predictedClose;

    @Column(name = "range_low", nullable = false, precision = 12, scale = 2)
    private BigDecimal rangeLow;

    @Column(name = "range_high", nullable = false, precision = 12, scale = 2)
    private BigDecimal rangeHigh;

    @Column(name = "bias_correction_applied", precision = 12, scale = 4)
    private BigDecimal biasCorrectionApplied;

    @Column(name = "actual_close", precision = 12, scale = 2)
    private BigDecimal actualClose;

    @Column(name = "error_pct", precision = 8, scale = 4)
    private BigDecimal errorPct;

    @Column(name = "evaluated_at")
    private OffsetDateTime evaluatedAt;
}
