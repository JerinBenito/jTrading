-- India VIX's own level, joined onto every instrument's daily feature row (same broadcast
-- pattern as the existing SP500/CRUDE_OIL/USD_INR fields). Added after backtesting 2 years of
-- real data: VIX level (not daily change) predicts NIFTY's own forward 10-day return
-- (Spearman +0.128, t=+2.82, p=0.005) and the same effect shows up in individual basket stocks
-- (27/50 individually significant at p<0.05).
ALTER TABLE ml_feature_snapshots ADD COLUMN global_india_vix_level NUMERIC(10,4);
