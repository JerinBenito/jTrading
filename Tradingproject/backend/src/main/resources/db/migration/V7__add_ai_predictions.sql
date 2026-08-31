-- Live prediction ledger for the AI (ML) model, separate from hourly_predictions (the
-- deterministic random-walk model's own table). Every prediction the model makes gets recorded
-- and later evaluated against the real outcome, whether it was right or wrong — the point is a
-- growing, honest track record judged on rolling accuracy over many predictions, never on any
-- single one. Same discipline hourly_predictions already applies to the deterministic model,
-- now extended to the AI model specifically.
CREATE TABLE ai_predictions (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    -- INTRADAY (same-day close) or FORWARD_5D / FORWARD_10D / FORWARD_20D / FORWARD_40D
    horizon VARCHAR(20) NOT NULL,
    -- PRICE for INTRADAY (predicted_value/baseline_value/actual_value are prices),
    -- RETURN_PCT for FORWARD_* (they're a percentage return over that horizon)
    value_type VARCHAR(20) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    predicted_at_ts TIMESTAMPTZ NOT NULL,
    -- the trading day the prediction was made/anchored on (evaluated same day for INTRADAY,
    -- N trading days later for FORWARD_*)
    target_date DATE NOT NULL,

    predicted_value NUMERIC(14,4) NOT NULL,
    baseline_value NUMERIC(14,4) NOT NULL,
    actual_value NUMERIC(14,4),

    evaluated_at TIMESTAMPTZ,
    ai_error_abs NUMERIC(14,4),
    baseline_error_abs NUMERIC(14,4),
    better_than_baseline BOOLEAN,
    direction_correct BOOLEAN,

    UNIQUE (instrument, horizon, target_date)
);
