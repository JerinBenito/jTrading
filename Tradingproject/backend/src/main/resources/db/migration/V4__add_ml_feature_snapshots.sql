-- Groundwork for a future ML phase (per project long-term roadmap) — a growing, labeled feature
-- dataset, decoupled from any live prediction logic. Nothing here feeds a live prediction; it
-- exists purely so 2+ years of engineered features + real forward-looking outcomes are already
-- available whenever a model actually gets trained, instead of needing to be reconstructed then.
CREATE TABLE ml_feature_snapshots (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    trading_date DATE NOT NULL,

    open NUMERIC(12,2) NOT NULL,
    high NUMERIC(12,2) NOT NULL,
    low NUMERIC(12,2) NOT NULL,
    close NUMERIC(12,2) NOT NULL,
    volume BIGINT,

    daily_return_pct NUMERIC(8,4),
    gap_from_prev_close_pct NUMERIC(8,4),
    intraday_range_pct NUMERIC(8,4),
    ema9 NUMERIC(14,4),
    ema21 NUMERIC(14,4),
    ema_spread_pct NUMERIC(8,4),
    rsi14 NUMERIC(8,4),
    atr14 NUMERIC(14,4),

    return_5d_pct NUMERIC(8,4),
    return_10d_pct NUMERIC(8,4),
    return_20d_pct NUMERIC(8,4),
    return_40d_pct NUMERIC(8,4),

    -- Nullable until that many trading days have actually passed since trading_date.
    forward_return_5d_pct NUMERIC(8,4),
    forward_return_10d_pct NUMERIC(8,4),
    forward_return_20d_pct NUMERIC(8,4),
    forward_return_40d_pct NUMERIC(8,4),

    computed_at TIMESTAMPTZ NOT NULL,
    UNIQUE (instrument, trading_date)
);
