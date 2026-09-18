-- Persist, per individual AI prediction call, (a) how far it sits from the day's FIRST call and from
-- the call just before it, and (b) its own realized error once the day's outcome is known. Until now
-- ai_prediction_snapshots kept only the raw predictions, so the first-call reference, the revision
-- gradient and every call's error had to be recomputed on demand and could never simply be looked at.
ALTER TABLE ai_prediction_snapshots
    ADD COLUMN sequence_in_day INTEGER,
    ADD COLUMN after_close BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN deviation_from_first NUMERIC(14, 4),
    ADD COLUMN deviation_from_previous NUMERIC(14, 4),
    ADD COLUMN actual_value NUMERIC(14, 4),
    ADD COLUMN error_signed NUMERIC(14, 4),
    ADD COLUMN error_abs NUMERIC(14, 4),
    ADD COLUMN error_pct NUMERIC(14, 4),
    ADD COLUMN baseline_error_abs NUMERIC(14, 4),
    ADD COLUMN better_than_baseline BOOLEAN,
    ADD COLUMN direction_correct BOOLEAN,
    ADD COLUMN evaluated_at TIMESTAMPTZ;

-- Rows recorded before this migration: derive sequence and deviations from the call order.
UPDATE ai_prediction_snapshots s
SET sequence_in_day = r.seq,
    deviation_from_first = r.predicted_value - r.first_pred,
    deviation_from_previous = CASE WHEN r.prev_value IS NULL THEN NULL ELSE r.predicted_value - r.prev_value END
FROM (
    SELECT id, predicted_value,
           ROW_NUMBER() OVER w AS seq,
           FIRST_VALUE(predicted_value) OVER w AS first_pred,
           LAG(predicted_value) OVER w AS prev_value
    FROM ai_prediction_snapshots
    WINDOW w AS (PARTITION BY instrument, horizon, target_date ORDER BY predicted_at_ts, id)
) r
WHERE s.id = r.id;

-- An INTRADAY call made after the 15:30 IST close already knows the close (see AiPredictionService),
-- so it is flagged rather than deleted: it stays visible but is excluded from every metric.
UPDATE ai_prediction_snapshots
SET after_close = TRUE
WHERE horizon = 'INTRADAY'
  AND (predicted_at_ts AT TIME ZONE 'Asia/Kolkata') > (target_date + TIME '15:30');

-- Days already evaluated in ai_predictions: stamp each snapshot's own error against that same outcome.
UPDATE ai_prediction_snapshots s
SET actual_value = p.actual_value,
    error_signed = s.predicted_value - p.actual_value,
    error_abs = ABS(s.predicted_value - p.actual_value),
    error_pct = CASE WHEN s.value_type = 'PRICE' AND p.actual_value <> 0
                     THEN (s.predicted_value - p.actual_value) / p.actual_value * 100 END,
    baseline_error_abs = ABS(s.baseline_value - p.actual_value),
    better_than_baseline = ABS(s.predicted_value - p.actual_value) < ABS(s.baseline_value - p.actual_value),
    direction_correct = (SIGN(p.actual_value - s.baseline_value) = SIGN(s.predicted_value - s.baseline_value)),
    evaluated_at = p.evaluated_at
FROM ai_predictions p
WHERE p.instrument = s.instrument
  AND p.horizon = s.horizon
  AND p.target_date = s.target_date
  AND p.actual_value IS NOT NULL;
