CREATE TABLE ohlcv_candles (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    interval VARCHAR(10) NOT NULL,
    ts TIMESTAMPTZ NOT NULL,
    open NUMERIC(12,2) NOT NULL,
    high NUMERIC(12,2) NOT NULL,
    low NUMERIC(12,2) NOT NULL,
    close NUMERIC(12,2) NOT NULL,
    volume BIGINT,
    UNIQUE (instrument, interval, ts)
);
CREATE INDEX idx_ohlcv_instrument_ts ON ohlcv_candles (instrument, ts);

CREATE TABLE option_chain_snapshot (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    expiry DATE NOT NULL,
    strike NUMERIC(12,2) NOT NULL,
    option_type VARCHAR(4) NOT NULL,
    ts TIMESTAMPTZ NOT NULL,
    oi BIGINT,
    change_oi BIGINT,
    iv NUMERIC(6,2),
    ltp NUMERIC(12,2),
    volume BIGINT
);
CREATE INDEX idx_opt_instrument_ts ON option_chain_snapshot (instrument, expiry, ts);

CREATE TABLE signal_predictions (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    ts TIMESTAMPTZ NOT NULL,
    pattern_id VARCHAR(50) NOT NULL,
    inputs_snapshot JSONB NOT NULL,
    predicted_direction VARCHAR(10) NOT NULL,
    confidence_tier VARCHAR(10) NOT NULL,
    sample_size_at_time INT NOT NULL
);
CREATE INDEX idx_predictions_instrument_ts ON signal_predictions (instrument, ts);
CREATE INDEX idx_predictions_pattern ON signal_predictions (pattern_id);

CREATE TABLE signal_outcomes (
    id BIGSERIAL PRIMARY KEY,
    prediction_id BIGINT NOT NULL UNIQUE REFERENCES signal_predictions(id),
    actual_direction VARCHAR(10),
    actual_move_pct NUMERIC(6,3),
    evaluated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE pattern_stats (
    pattern_id VARCHAR(50) NOT NULL,
    window_start DATE NOT NULL,
    window_end DATE NOT NULL,
    sample_size INT NOT NULL,
    win_rate NUMERIC(5,2),
    avg_move_pct NUMERIC(6,3),
    last_recalibrated TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (pattern_id, window_end)
);
