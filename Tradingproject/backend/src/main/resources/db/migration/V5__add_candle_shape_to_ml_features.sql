-- Adds daily-candle shape features to the ML groundwork dataset, per Jerin's request to
-- specifically capture chart/candle shape (not just derived indicators) for future ML use —
-- even though testing it as a manual k-NN rule (ChartShapeAnalogBacktestService) found no edge.
ALTER TABLE ml_feature_snapshots
    ADD COLUMN body_pct NUMERIC(8,4),
    ADD COLUMN upper_wick_pct NUMERIC(8,4),
    ADD COLUMN lower_wick_pct NUMERIC(8,4);
