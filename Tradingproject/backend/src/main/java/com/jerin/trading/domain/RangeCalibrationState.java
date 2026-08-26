package com.jerin.trading.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Current online-calibrated ATR multiplier per instrument+interval — one row each, updated in place as evaluations come in. */
@Entity
@Table(name = "range_calibration_state", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "interval"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RangeCalibrationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal multiplier;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
