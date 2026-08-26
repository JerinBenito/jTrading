package com.jerin.trading.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "option_chain_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptionChainSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(nullable = false)
    private LocalDate expiry;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal strike;

    @Column(name = "option_type", nullable = false, length = 4)
    private String optionType;

    @Column(nullable = false)
    private OffsetDateTime ts;

    private Long oi;

    @Column(name = "change_oi")
    private Long changeOi;

    @Column(precision = 6, scale = 2)
    private BigDecimal iv;

    @Column(precision = 12, scale = 2)
    private BigDecimal ltp;

    private Long volume;
}
