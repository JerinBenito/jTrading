-- The "nudge" is how far a call's prediction sits from that same call's own baseline (the price at the
-- moment it was made): the only number that says whether the AI chose to deviate from "assume no
-- change". Deviation-from-first and deviation-from-previous were already stored as raw price
-- differences; the percentage forms make them comparable across instruments and usable as features.
ALTER TABLE ai_prediction_snapshots
    ADD COLUMN nudge NUMERIC(14, 4),
    ADD COLUMN nudge_pct NUMERIC(14, 4),
    ADD COLUMN deviation_from_first_pct NUMERIC(14, 4),
    ADD COLUMN deviation_from_previous_pct NUMERIC(14, 4);

-- Percentages are only meaningful for PRICE-typed calls; RETURN_PCT calls are already percent-scale.
UPDATE ai_prediction_snapshots
SET nudge = predicted_value - baseline_value,
    nudge_pct = CASE WHEN value_type = 'PRICE' AND baseline_value <> 0
                     THEN (predicted_value - baseline_value) / baseline_value * 100 END,
    deviation_from_first_pct = CASE WHEN value_type = 'PRICE' AND deviation_from_first IS NOT NULL
                                         AND (predicted_value - deviation_from_first) <> 0
                                    THEN deviation_from_first / (predicted_value - deviation_from_first) * 100 END,
    deviation_from_previous_pct = CASE WHEN value_type = 'PRICE' AND deviation_from_previous IS NOT NULL
                                            AND (predicted_value - deviation_from_previous) <> 0
                                       THEN deviation_from_previous / (predicted_value - deviation_from_previous) * 100 END;
