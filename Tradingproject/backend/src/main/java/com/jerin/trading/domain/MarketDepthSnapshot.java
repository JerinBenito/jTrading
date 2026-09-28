package com.jerin.trading.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One live order-book snapshot for one instrument — captured periodically going forward, never
 * backfillable for past dates (see {@link com.jerin.trading.broker.BrokerClient#getMarketQuotes}).
 * Purely observational for now, same spirit {@link com.jerin.trading.ml.AiPredictionService} had
 * when it first launched: start collecting a real, honest history before anyone builds a feature
 * or a claim on top of it. Needs weeks/months of accumulation before {@code imbalance} can be
 * tested for any real relationship to subsequent price moves.
 */
@Entity
@Table(name = "market_depth_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketDepthSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(nullable = false)
    private OffsetDateTime ts;

    @Column(name = "last_price", precision = 14, scale = 4)
    private BigDecimal lastPrice;

    @Column(name = "total_buy_quantity")
    private Long totalBuyQuantity;

    @Column(name = "total_sell_quantity")
    private Long totalSellQuantity;

    /** (buy - sell) / (buy + sell) — the standard order-flow-imbalance definition. +1 = entirely
     * buy-side interest, -1 = entirely sell-side, 0 = balanced. Null if both quantities are zero. */
    @Column(precision = 8, scale = 4)
    private BigDecimal imbalance;

    @Column(name = "top_bid_price", precision = 14, scale = 4)
    private BigDecimal topBidPrice;

    @Column(name = "top_bid_quantity")
    private Long topBidQuantity;

    @Column(name = "top_ask_price", precision = 14, scale = 4)
    private BigDecimal topAskPrice;

    @Column(name = "top_ask_quantity")
    private Long topAskQuantity;
}
