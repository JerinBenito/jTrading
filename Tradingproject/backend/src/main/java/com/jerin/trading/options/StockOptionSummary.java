package com.jerin.trading.options;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One captured summary of a stock's option chain - see {@link StockOptionSummaryService}. */
@Entity
@Table(name = "stock_option_summaries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockOptionSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "captured_at", nullable = false)
    private OffsetDateTime capturedAt;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(name = "days_to_expiry", nullable = false)
    private int daysToExpiry;

    private BigDecimal spot;

    @Column(name = "atm_strike")
    private BigDecimal atmStrike;

    @Column(name = "call_iv")
    private BigDecimal callIv;

    @Column(name = "put_iv")
    private BigDecimal putIv;

    @Column(name = "atm_iv")
    private BigDecimal atmIv;

    /** Put implied vol about 5% below the spot minus call implied vol about 5% above (positive = downside is priced richer). */
    private BigDecimal skew;

    @Column(name = "pcr_oi")
    private BigDecimal pcrOi;

    @Column(name = "total_oi")
    private Long totalOi;

    @Column(name = "straddle_pct")
    private BigDecimal straddlePct;
}
