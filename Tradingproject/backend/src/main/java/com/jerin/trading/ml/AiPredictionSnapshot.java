package com.jerin.trading.ml;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One immutable record of a single AI prediction call — inserted, never updated, unlike
 * {@link AiPrediction} which upserts by (instrument, horizon, target_date) and only ever keeps
 * the latest call. Exists specifically so the day's FIRST prediction (made with the least
 * information, the fair test of real skill) isn't lost once later hourly re-predictions
 * overwrite it — later calls trivially converge toward the actual price as the day progresses,
 * so averaging them in with the first call inflates the AI's apparent accuracy without it having
 * done anything more skillful.
 */
@Entity
@Table(name = "ai_prediction_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPredictionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(nullable = false, length = 20)
    private String horizon;

    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;

    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    @Column(name = "predicted_at_ts", nullable = false)
    private OffsetDateTime predictedAtTs;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "predicted_value", nullable = false, precision = 14, scale = 4)
    private BigDecimal predictedValue;

    @Column(name = "baseline_value", nullable = false, precision = 14, scale = 4)
    private BigDecimal baselineValue;

    @Column(name = "predicted_price", precision = 14, scale = 4)
    private BigDecimal predictedPrice;

    @Column(name = "baseline_price", precision = 14, scale = 4)
    private BigDecimal baselinePrice;
}
