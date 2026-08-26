CREATE TABLE hourly_predictions (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    interval VARCHAR(10) NOT NULL,
    model_name VARCHAR(50) NOT NULL,
    predicted_at_ts TIMESTAMPTZ NOT NULL,
    predicted_for_ts TIMESTAMPTZ NOT NULL,
    predicted_close NUMERIC(12,2) NOT NULL,
    range_low NUMERIC(12,2) NOT NULL,
    range_high NUMERIC(12,2) NOT NULL,
    bias_correction_applied NUMERIC(12,4),
    actual_close NUMERIC(12,2),
    error_pct NUMERIC(8,4),
    evaluated_at TIMESTAMPTZ,
    UNIQUE (instrument, interval, predicted_for_ts)
);
CREATE INDEX idx_hourly_predictions_instrument_ts ON hourly_predictions (instrument, interval, predicted_for_ts);
