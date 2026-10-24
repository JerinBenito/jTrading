package com.jerin.trading.risk;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One same-day remaining-swing forecast - see {@link IntradayRiskController}. */
@Entity
@Table(name = "intraday_risk_forecasts", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "target_date", "hours_since_open"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntradayRiskForecast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "target_date", nullable = false)
    private LocalDate targetDate;

    @Column(name = "hours_since_open", nullable = false)
    private int hoursSinceOpen;

    @Column(name = "predicted_at", nullable = false)
    private OffsetDateTime predictedAt;

    @Column(name = "current_price")
    private BigDecimal currentPrice;

    @Column(name = "expected_swing_pct", nullable = false)
    private BigDecimal expectedSwingPct;

    @Column(name = "upper90_swing_pct", nullable = false)
    private BigDecimal upper90SwingPct;

    @Column(name = "model_version", length = 80)
    private String modelVersion;
}
