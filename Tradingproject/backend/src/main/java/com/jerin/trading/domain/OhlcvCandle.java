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
@Table(name = "ohlcv_candles", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "interval", "ts"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OhlcvCandle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "interval", nullable = false, length = 10)
    private String interval;

    @Column(nullable = false)
    private OffsetDateTime ts;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal open;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal high;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal low;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal close;

    private Long volume;
}
