-- Same-day "remaining swing" forecasts: at each hourly checkpoint, how far (high to low, % of price) a stock is expected
-- to still move between now and the close, plus the 90th-percentile figure. Written by ml-training/run_daily_ai_prediction.py
-- (one row per instrument, trading date and hour-since-open; a later run in the same hour replaces it). Descriptive risk
-- context, not a price prediction. Walk-forward test (600 days, 150k checkpoints): rank corr 0.64, 90% figure holds 90.9%.
CREATE TABLE intraday_risk_forecasts (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    target_date DATE NOT NULL,
    hours_since_open INT NOT NULL,
    predicted_at TIMESTAMPTZ NOT NULL,
    current_price NUMERIC(14,4),
    expected_swing_pct NUMERIC(8,4) NOT NULL,
    upper90_swing_pct NUMERIC(8,4) NOT NULL,
    model_version VARCHAR(80),
    CONSTRAINT uq_intraday_risk UNIQUE (instrument, target_date, hours_since_open)
);

CREATE INDEX idx_intraday_risk_date ON intraday_risk_forecasts (target_date, instrument);
