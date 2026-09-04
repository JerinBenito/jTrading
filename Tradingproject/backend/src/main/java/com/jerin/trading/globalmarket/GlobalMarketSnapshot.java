package com.jerin.trading.globalmarket;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** One overnight global-market read (see {@link GlobalMarketDataService}) — purely observational
 * until there's enough accumulated history to test it as an actual signal. */
@Entity
@Table(name = "global_market_snapshot", uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "trading_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalMarketSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    @Column(nullable = false, precision = 14, scale = 4)
    private BigDecimal price;

    @Column(name = "change_pct", precision = 8, scale = 4)
    private BigDecimal changePct;

    @Column(name = "previous_close", precision = 14, scale = 4)
    private BigDecimal previousClose;
}
