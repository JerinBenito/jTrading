-- The AI's point prediction (ai_predictions.predicted_value) has had no notion of uncertainty at
-- all — a single number, no range. This adds a learned range alongside it: two extra models
-- (quantile regression, not the fixed-formula width the deterministic model uses) predict where
-- the outcome is likely to fall, so the range can genuinely narrow or widen based on what the
-- model has learned about the current situation, not just the time of day.
ALTER TABLE ai_predictions
    ADD COLUMN predicted_range_low NUMERIC(14, 4),
    ADD COLUMN predicted_range_high NUMERIC(14, 4),
    ADD COLUMN within_predicted_range BOOLEAN;

ALTER TABLE ai_prediction_snapshots
    ADD COLUMN predicted_range_low NUMERIC(14, 4),
    ADD COLUMN predicted_range_high NUMERIC(14, 4),
    ADD COLUMN within_predicted_range BOOLEAN;
