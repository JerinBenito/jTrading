-- The monthly (FORWARD_*) predictions only stored a % return, no actual rupee price. These
-- columns give every prediction (both INTRADAY and FORWARD_*) a real price value regardless of
-- valueType: for INTRADAY it's just a copy of predicted_value/baseline_value/actual_value (which
-- are already prices); for FORWARD_* it's the % return converted using the price the prediction
-- was anchored from.
ALTER TABLE ai_predictions
    ADD COLUMN predicted_price NUMERIC(14,4),
    ADD COLUMN baseline_price NUMERIC(14,4),
    ADD COLUMN actual_price NUMERIC(14,4);
