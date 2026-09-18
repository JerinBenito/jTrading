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

    /** 1 for the day's first call, 2 for the next, and so on. */
    @Column(name = "sequence_in_day")
    private Integer sequenceInDay;

    /** INTRADAY only: recorded after the 15:30 IST close, so it already knew the close. Kept for
     * visibility, excluded from every metric and feature. */
    @Column(name = "after_close", nullable = false)
    private boolean afterClose;

    /** This call's predicted value minus the day's FIRST call's — signed, so the direction the AI
     * revised in is kept, not just how far. Zero on the first call itself. */
    @Column(name = "deviation_from_first", precision = 14, scale = 4)
    private BigDecimal deviationFromFirst;

    /** This call's predicted value minus the call immediately before it; null on the first call. */
    @Column(name = "deviation_from_previous", precision = 14, scale = 4)
    private BigDecimal deviationFromPrevious;

    /** predicted - this call's own baseline: how far the AI chose to deviate from "assume no
     * change" at the moment it made this call. */
    @Column(name = "nudge", precision = 14, scale = 4)
    private BigDecimal nudge;

    /** {@link #nudge} as % of the baseline — PRICE-typed calls only. */
    @Column(name = "nudge_pct", precision = 14, scale = 4)
    private BigDecimal nudgePct;

    /** {@link #deviationFromFirst} as % of the day's first call — PRICE-typed calls only. */
    @Column(name = "deviation_from_first_pct", precision = 14, scale = 4)
    private BigDecimal deviationFromFirstPct;

    /** {@link #deviationFromPrevious} as % of the previous call — PRICE-typed calls only. */
    @Column(name = "deviation_from_previous_pct", precision = 14, scale = 4)
    private BigDecimal deviationFromPreviousPct;

    // Filled in once the target day's outcome is known (see AiPredictionService#evaluatePending) —
    // every call gets its own error, not only the latest one.
    @Column(name = "actual_value", precision = 14, scale = 4)
    private BigDecimal actualValue;

    /** predicted - actual: positive means the AI predicted too high, negative too low. */
    @Column(name = "error_signed", precision = 14, scale = 4)
    private BigDecimal errorSigned;

    @Column(name = "error_abs", precision = 14, scale = 4)
    private BigDecimal errorAbs;

    /** Signed error as % of the actual — PRICE-typed calls only, null for RETURN_PCT. */
    @Column(name = "error_pct", precision = 14, scale = 4)
    private BigDecimal errorPct;

    @Column(name = "baseline_error_abs", precision = 14, scale = 4)
    private BigDecimal baselineErrorAbs;

    @Column(name = "better_than_baseline")
    private Boolean betterThanBaseline;

    @Column(name = "direction_correct")
    private Boolean directionCorrect;

    @Column(name = "evaluated_at")
    private OffsetDateTime evaluatedAt;
}
