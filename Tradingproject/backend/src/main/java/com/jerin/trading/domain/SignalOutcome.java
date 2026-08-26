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
@Table(name = "signal_outcomes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prediction_id", nullable = false, unique = true)
    private Long predictionId;

    @Column(name = "actual_direction", length = 10)
    private String actualDirection;

    @Column(name = "actual_move_pct", precision = 6, scale = 3)
    private BigDecimal actualMovePct;

    @Column(name = "evaluated_at", nullable = false)
    private OffsetDateTime evaluatedAt;
}
