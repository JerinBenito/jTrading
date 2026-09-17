-- Records every individual AI prediction call (each hourly re-prediction through the day),
-- never overwritten — unlike ai_predictions, which upserts by (instrument, horizon, target_date)
-- and therefore only ever keeps the latest call. Needed to fairly judge the AI on its FIRST,
-- hardest-information call of the day rather than an average that includes later re-predictions
-- which trivially converge toward the actual price as the day progresses.
CREATE TABLE ai_prediction_snapshots (
    id BIGSERIAL PRIMARY KEY,
    instrument VARCHAR(50) NOT NULL,
    horizon VARCHAR(20) NOT NULL,
    value_type VARCHAR(20) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    predicted_at_ts TIMESTAMPTZ NOT NULL,
    target_date DATE NOT NULL,
    predicted_value NUMERIC(14, 4) NOT NULL,
    baseline_value NUMERIC(14, 4) NOT NULL,
    predicted_price NUMERIC(14, 4),
    baseline_price NUMERIC(14, 4)
);

CREATE INDEX idx_ai_prediction_snapshots_lookup
    ON ai_prediction_snapshots (instrument, horizon, target_date, predicted_at_ts);
