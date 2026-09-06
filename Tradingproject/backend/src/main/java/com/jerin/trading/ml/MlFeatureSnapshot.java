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
 * One row per (instrument, trading day): engineered features as of that day's close, plus
 * forward-looking return labels filled in once that many trading days have actually passed.
 * Purely a growing labeled dataset for a future ML phase — never read by any live prediction.
 */
@Entity
@Table(name = "ml_feature_snapshots", uniqueConstraints = @UniqueConstraint(columnNames = {"instrument", "trading_date"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MlFeatureSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String instrument;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal open;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal high;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal low;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal close;
    private Long volume;

    @Column(name = "daily_return_pct", precision = 8, scale = 4)
    private BigDecimal dailyReturnPct;
    @Column(name = "gap_from_prev_close_pct", precision = 8, scale = 4)
    private BigDecimal gapFromPrevClosePct;
    @Column(name = "intraday_range_pct", precision = 8, scale = 4)
    private BigDecimal intradayRangePct;
    @Column(precision = 14, scale = 4)
    private BigDecimal ema9;
    @Column(precision = 14, scale = 4)
    private BigDecimal ema21;
    @Column(name = "ema_spread_pct", precision = 8, scale = 4)
    private BigDecimal emaSpreadPct;
    @Column(name = "rsi14", precision = 8, scale = 4)
    private BigDecimal rsi14;
    @Column(name = "atr14", precision = 14, scale = 4)
    private BigDecimal atr14;

    /** Daily candle shape — signed body dominance and wick proportions. Tested as a manual k-NN
     * feature ({@link com.jerin.trading.forecast.ChartShapeAnalogBacktestService}) and found no
     * edge there, but kept here as raw material for a future ML model that can find nonlinear
     * interactions a nearest-neighbor average cannot. */
    @Column(name = "body_pct", precision = 8, scale = 4)
    private BigDecimal bodyPct;
    @Column(name = "upper_wick_pct", precision = 8, scale = 4)
    private BigDecimal upperWickPct;
    @Column(name = "lower_wick_pct", precision = 8, scale = 4)
    private BigDecimal lowerWickPct;

    /** Today's volume / trailing 20-day average daily volume. Null for NIFTY/BANKNIFTY (no real
     * index volume) or before 20 prior days exist. */
    @Column(name = "volume_ratio_20d", precision = 10, scale = 4)
    private BigDecimal volumeRatio20d;

    @Column(name = "return_5d_pct", precision = 8, scale = 4)
    private BigDecimal return5dPct;
    @Column(name = "return_10d_pct", precision = 8, scale = 4)
    private BigDecimal return10dPct;
    @Column(name = "return_20d_pct", precision = 8, scale = 4)
    private BigDecimal return20dPct;
    @Column(name = "return_40d_pct", precision = 8, scale = 4)
    private BigDecimal return40dPct;

    @Column(name = "forward_return_5d_pct", precision = 8, scale = 4)
    private BigDecimal forwardReturn5dPct;
    @Column(name = "forward_return_10d_pct", precision = 8, scale = 4)
    private BigDecimal forwardReturn10dPct;
    @Column(name = "forward_return_20d_pct", precision = 8, scale = 4)
    private BigDecimal forwardReturn20dPct;
    @Column(name = "forward_return_40d_pct", precision = 8, scale = 4)
    private BigDecimal forwardReturn40dPct;

    /** Everything else this codebase knows about this instrument on this day — see
     * {@link SupplementaryFeatures}/{@link SupplementaryFeatureService} for what each of these
     * means and why most will be null on older rows. */
    @Column(name = "deterministic_deviation_pct", precision = 10, scale = 4)
    private BigDecimal deterministicDeviationPct;
    @Column(name = "hmm_deviation_pct", precision = 10, scale = 4)
    private BigDecimal hmmDeviationPct;
    @Column(name = "garch_range_width_pct", precision = 10, scale = 4)
    private BigDecimal garchRangeWidthPct;
    @Column(name = "pcr_latest", precision = 10, scale = 4)
    private BigDecimal pcrLatest;
    @Column(name = "global_sp500_change_pct", precision = 10, scale = 4)
    private BigDecimal globalSp500ChangePct;
    @Column(name = "global_crude_oil_change_pct", precision = 10, scale = 4)
    private BigDecimal globalCrudeOilChangePct;
    @Column(name = "global_usd_inr_change_pct", precision = 10, scale = 4)
    private BigDecimal globalUsdInrChangePct;
    @Column(name = "fundamental_pe", precision = 10, scale = 4)
    private BigDecimal fundamentalPe;
    @Column(name = "fundamental_roe", precision = 10, scale = 4)
    private BigDecimal fundamentalRoe;
    @Column(name = "recent_pattern_win_rate", precision = 6, scale = 2)
    private BigDecimal recentPatternWinRate;
    @Column(name = "recent_pattern_direction")
    private Integer recentPatternDirection;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;
}
