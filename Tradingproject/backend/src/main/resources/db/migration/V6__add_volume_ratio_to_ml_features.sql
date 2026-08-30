-- Volume feature, per Jerin's request to try data already ingested but never used as an ML
-- feature. Null for NIFTY/BANKNIFTY (the index itself carries no real volume) and for the first
-- 20 trading days of any instrument's history (no trailing average yet).
ALTER TABLE ml_feature_snapshots
    ADD COLUMN volume_ratio_20d NUMERIC(10,4);
