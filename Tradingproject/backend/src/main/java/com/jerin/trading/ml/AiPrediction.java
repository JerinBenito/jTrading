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
 * One AI (ML) model prediction and, once known, its real outcome — the live counterpart to the
 * offline backtests in {@code ml-training/}. Every prediction gets recorded and evaluated
 * whether it turns out right or wrong; judged over many predictions via rolling accuracy
 * ({@link AiPredictionService#rollingAccuracy}), never on any single one. Explicitly NOT proven
 * to have skill as of 2026-08-30 (offline walk-forward validation found no edge over the
 * baseline) — this ledger exists for honest, ongoing observation, not because the model is
 * trusted yet.
 */
@Entity
@Table(name = "ai_predictions", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "horizon", "target_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    /** INTRADAY, FORWARD_5D, FORWARD_10D, FORWARD_20D, or FORWARD_40D. */
    @Column(nullable = false, length = 20)
    private String horizon;

    /** PRICE (INTRADAY) or RETURN_PCT (FORWARD_*). */
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

    @Column(name = "actual_value", precision = 14, scale = 4)
    private BigDecimal actualValue;

    /** Real rupee price regardless of {@link #valueType} — for INTRADAY this equals
     * predicted/baseline/actual_value directly (already a price); for FORWARD_* it's the %
     * return converted using the price the prediction was anchored from, so a consumer never
     * has to branch on valueType to get an actual price. */
    @Column(name = "predicted_price", precision = 14, scale = 4)
    private BigDecimal predictedPrice;

    @Column(name = "baseline_price", precision = 14, scale = 4)
    private BigDecimal baselinePrice;

    @Column(name = "actual_price", precision = 14, scale = 4)
    private BigDecimal actualPrice;

    @Column(name = "evaluated_at")
    private OffsetDateTime evaluatedAt;

    @Column(name = "ai_error_abs", precision = 14, scale = 4)
    private BigDecimal aiErrorAbs;

    @Column(name = "baseline_error_abs", precision = 14, scale = 4)
    private BigDecimal baselineErrorAbs;

    @Column(name = "better_than_baseline")
    private Boolean betterThanBaseline;

    @Column(name = "direction_correct")
    private Boolean directionCorrect;
}
